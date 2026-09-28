import base64
import binascii
from datetime import timezone
from typing import Annotated
from urllib.parse import urlsplit
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.authority import append_admin_event, load_signing_key, lock_ledger
from app.config import Settings
from app.database import get_session
from app.models import Administrator, Appointment, AuthorityKey, Club, User
from app.session import current_user
from app.verification import normalize_institute_email

router = APIRouter(prefix="/api/admin", tags=["administration"])


def admin_settings() -> Settings:
    return Settings()


def require_admin(request: Request, response: Response,
                  user: Annotated[User, Depends(current_user)],
                  db: Annotated[Session, Depends(get_session)],
                  settings: Annotated[Settings, Depends(admin_settings)]) -> Administrator:
    response.headers["Cache-Control"] = "no-store"
    if request.method != "GET":
        origin = urlsplit(str(settings.public_app_url))
        if request.headers.get("origin") != f"{origin.scheme}://{origin.netloc}":
            raise HTTPException(403, "A same-origin request is required.")
        lock_ledger(db)
    admin = db.scalar(select(Administrator).where(Administrator.user_id == user.id,
                                                 Administrator.revoked_at.is_(None)))
    if admin is None or user.email_verified_at is None:
        raise HTTPException(403, "Institute administrator access is required.")
    key = db.get(AuthorityKey, admin.key_id)
    if key is None or key.revoked_at is not None:
        raise HTTPException(403, "Administrator signing authority is inactive.")
    return admin


Admin = Annotated[Administrator, Depends(require_admin)]
Database = Annotated[Session, Depends(get_session)]
Configuration = Annotated[Settings, Depends(admin_settings)]


class ClubRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    slug: str = Field(min_length=1, max_length=100, pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    name: str = Field(min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=2000)


class AdvisorRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    email: str = Field(max_length=254)
    public_key: str = Field(max_length=44)
    verification_reference: str = Field(min_length=1, max_length=500)
    starts_at: AwareDatetime
    ends_at: AwareDatetime

    @field_validator("email")
    @classmethod
    def institute_email(cls, value: str) -> str:
        return normalize_institute_email(value)

    @field_validator("public_key")
    @classmethod
    def ed25519_key(cls, value: str) -> str:
        try:
            raw = base64.b64decode(value, validate=True)
        except (ValueError, binascii.Error):
            raise ValueError("Provide a base64 Ed25519 public key") from None
        if len(raw) != 32 or raw == bytes(32):
            raise ValueError("Provide a 32-byte Ed25519 public key")
        return base64.b64encode(raw).decode("ascii")

    @model_validator(mode="after")
    def term_order(self):
        if self.ends_at <= self.starts_at:
            raise ValueError("End time must be after start time")
        return self


def signer(db: Session, admin: Administrator, settings: Settings):
    try:
        private_key = load_signing_key(settings.admin_signing_key_file)
        key = db.get(AuthorityKey, admin.key_id)
        if private_key.public_key().public_bytes_raw() != key.public_key:
            raise ValueError("Key mismatch")
        return key, private_key
    except (OSError, ValueError, TypeError):
        raise HTTPException(503, "Administrator signing key is unavailable or mismatched.") from None


@router.get("/access")
def access(admin: Admin):
    return {"is_admin": True}


@router.get("/clubs")
def list_clubs(admin: Admin, db: Database):
    clubs = db.scalars(select(Club).order_by(Club.name)).all()
    appointments = db.execute(select(Appointment, User).join(User, User.id == Appointment.user_id)
                             .where(Appointment.position == "faculty_advisor")
                             .order_by(Appointment.starts_at)).all()
    now = db.scalar(select(func.clock_timestamp()))
    return [{"id": str(club.id), "slug": club.slug, "name": club.name,
             "description": club.description,
             "advisors": [{"id": str(a.id), "email": user.email, "name": user.display_name,
                           "starts_at": a.starts_at, "ends_at": a.ends_at,
                           "status": "revoked" if a.revoked_at else "expired" if a.ends_at <= now
                           else "scheduled" if a.starts_at > now else "active"}
                          for a, user in appointments if a.club_id == club.id]} for club in clubs]


@router.post("/clubs", status_code=201)
def create_club(body: ClubRequest, admin: Admin, db: Database, settings: Configuration):
    if db.scalar(select(Club.id).where(Club.slug == body.slug)):
        raise HTTPException(409, "A club with this slug already exists.")
    key, private_key = signer(db, admin, settings)
    club = Club(id=uuid4(), **body.model_dump(), created_by_id=admin.user_id)
    db.add(club)
    append_admin_event(db, key, private_key, "CLUB_CREATED", {"club_id": str(club.id)},
                       {"slug": club.slug, "name": club.name, "description": club.description})
    db.commit()
    return {"id": str(club.id), "name": club.name, "slug": club.slug}


@router.post("/clubs/{club_id}/advisors", status_code=201)
def appoint_advisor(club_id: UUID, body: AdvisorRequest, admin: Admin, db: Database, settings: Configuration):
    if db.get(Club, club_id) is None:
        raise HTTPException(404, "Club not found.")
    now = db.scalar(select(func.clock_timestamp()))
    if body.ends_at <= now:
        raise HTTPException(422, "The appointment must end in the future.")
    user = db.scalar(select(User).where(User.email == body.email))
    if user is None or user.email_verified_at is None:
        raise HTTPException(422, "The advisor must first verify their institute account.")
    starts_at = max(body.starts_at, now)
    overlap = db.scalar(select(Appointment.id).where(
        Appointment.club_id == club_id, Appointment.position == "faculty_advisor",
        Appointment.revoked_at.is_(None), Appointment.starts_at < body.ends_at,
        Appointment.ends_at > starts_at,
    ))
    if overlap:
        raise HTTPException(409, "This club already has an advisor during the requested term.")
    signing_key, private_key = signer(db, admin, settings)
    raw_key = base64.b64decode(body.public_key)
    advisor_key = db.scalar(select(AuthorityKey).where(AuthorityKey.public_key == raw_key))
    if advisor_key and (advisor_key.user_id != user.id or advisor_key.revoked_at is not None
                        or advisor_key.purpose != "advisor"):
        raise HTTPException(409, "This public key cannot be assigned to this advisor.")
    if advisor_key is None:
        advisor_key = AuthorityKey(id=uuid4(), user_id=user.id, public_key=raw_key, purpose="advisor",
                                   verified_by_id=admin.user_id, verification_reference=body.verification_reference)
        db.add(advisor_key)
        db.flush()
    appointment_id = uuid4()
    # Never backdate authority: a past requested start becomes the actual grant time.
    event = append_admin_event(db, signing_key, private_key, "ADVISOR_APPOINTED",
        {"club_id": str(club_id), "user_id": str(user.id), "appointment_id": str(appointment_id)},
        {"position": "faculty_advisor", "key_id": str(advisor_key.id), "public_key": body.public_key,
         "starts_at": starts_at.astimezone(timezone.utc).isoformat(),
         "ends_at": body.ends_at.astimezone(timezone.utc).isoformat()})
    db.add(Appointment(id=appointment_id, club_id=club_id, user_id=user.id, position="faculty_advisor",
                       granted_by_id=admin.user_id, starts_at=starts_at, ends_at=body.ends_at,
                       advisor_key_id=advisor_key.id, verification_reference=body.verification_reference,
                       grant_transaction_id=event.transaction_id))
    db.commit()
    return {"id": str(appointment_id), "club_id": str(club_id), "advisor_id": str(user.id),
            "key_id": str(advisor_key.id), "starts_at": starts_at, "ends_at": body.ends_at}

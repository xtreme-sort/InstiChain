import base64
import binascii
from datetime import timedelta
from typing import Annotated, Literal
from uuid import NAMESPACE_URL, UUID, uuid5

import rfc8785
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import APIRouter, Depends, HTTPException
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.authority import FabricUnavailable, append_signed_event
from app.config import Settings
from app.database import get_session
from app.models import Appointment, AuthorityKey, User
from app.permissions import (
    AdvisorAuthority,
    ClubAuthority,
    permission_settings,
    require_advisor,
    require_club_advisor,
)
from app.verification import normalize_institute_email

router = APIRouter(prefix="/api/advisor", tags=["advisor"])


class AdvisorClubResponse(BaseModel):
    id: UUID
    slug: str
    name: str
    description: str | None
    appointment_id: UUID
    starts_at: AwareDatetime
    ends_at: AwareDatetime


Advisor = Annotated[AdvisorAuthority, Depends(require_advisor)]
ClubAdvisor = Annotated[ClubAuthority, Depends(require_club_advisor)]
Database = Annotated[Session, Depends(get_session)]
Configuration = Annotated[Settings, Depends(permission_settings)]


class RoleGrantDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    email: str = Field(max_length=254)
    position: Literal["club_head", "office_bearer"]
    starts_at: AwareDatetime
    ends_at: AwareDatetime

    @field_validator("email")
    @classmethod
    def institute_email(cls, value: str) -> str:
        return normalize_institute_email(value)

    @model_validator(mode="after")
    def term_order(self):
        if self.ends_at <= self.starts_at:
            raise ValueError("End time must be after start time")
        return self


class RoleObjectIds(BaseModel):
    model_config = ConfigDict(extra="forbid")
    club_id: UUID
    appointment_id: UUID
    user_id: UUID


class RolePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    position: Literal["club_head", "office_bearer"]
    prepared_at: AwareDatetime
    starts_at: AwareDatetime
    ends_at: AwareDatetime

    @model_validator(mode="after")
    def term_order(self):
        if self.ends_at <= self.starts_at:
            raise ValueError("End time must be after start time")
        return self


class RoleGrantDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: UUID
    event_type: Literal["ROLE_GRANTED"]
    object_ids: RoleObjectIds
    payload: RolePayload
    actor_id: UUID
    actor_key_id: UUID


class RoleGrantPreparation(BaseModel):
    document: RoleGrantDocument
    signing_payload: str


class RoleGrantSubmission(BaseModel):
    model_config = ConfigDict(extra="forbid")
    document: RoleGrantDocument
    signature: str

    @field_validator("signature")
    @classmethod
    def ed25519_signature(cls, value: str) -> str:
        try:
            signature = base64.b64decode(value, validate=True)
        except (ValueError, binascii.Error):
            raise ValueError("Provide a base64 Ed25519 signature") from None
        if len(signature) != 64:
            raise ValueError("Provide a 64-byte Ed25519 signature")
        return base64.b64encode(signature).decode("ascii")


class RoleGrantResponse(BaseModel):
    id: UUID
    club_id: UUID
    user_id: UUID
    position: str
    starts_at: AwareDatetime
    ends_at: AwareDatetime


def club_response(authority: ClubAuthority) -> AdvisorClubResponse:
    return AdvisorClubResponse(
        id=authority.club.id,
        slug=authority.club.slug,
        name=authority.club.name,
        description=authority.club.description,
        appointment_id=authority.appointment.id,
        starts_at=authority.appointment.starts_at,
        ends_at=authority.appointment.ends_at,
    )


def document_dict(document: RoleGrantDocument) -> dict:
    return document.model_dump(mode="json")


def advisor_key(db: Session, authority: ClubAuthority) -> AuthorityKey:
    key = db.get(AuthorityKey, authority.appointment.advisor_key_id)
    if (key is None or key.user_id != authority.user.id or key.purpose != "advisor"
            or key.revoked_at is not None):
        raise HTTPException(403, "Advisor signing authority is inactive.")
    return key


def role_grant_response(appointment: Appointment) -> RoleGrantResponse:
    return RoleGrantResponse(
        id=appointment.id,
        club_id=appointment.club_id,
        user_id=appointment.user_id,
        position=appointment.position,
        starts_at=appointment.starts_at,
        ends_at=appointment.ends_at,
    )


@router.get("/access")
def access(advisor: Advisor):
    return {"is_advisor": True}


@router.get("/clubs", response_model=list[AdvisorClubResponse])
def assigned_clubs(advisor: Advisor):
    return [
        AdvisorClubResponse(
            id=appointment.club_id,
            slug=appointment.club.slug,
            name=appointment.club.name,
            description=appointment.club.description,
            appointment_id=appointment.id,
            starts_at=appointment.starts_at,
            ends_at=appointment.ends_at,
        )
        for appointment in advisor.appointments
    ]


@router.get("/clubs/{club_id}/access", response_model=AdvisorClubResponse)
def club_access(club_id: UUID, advisor: ClubAdvisor):
    return club_response(advisor)


@router.post("/clubs/{club_id}/role-grants/prepare", response_model=RoleGrantPreparation)
def prepare_role_grant(
    club_id: UUID,
    body: RoleGrantDraft,
    advisor: ClubAdvisor,
    db: Database,
):
    recipient = db.scalar(select(User).where(User.email == body.email))
    if recipient is None or recipient.email_verified_at is None:
        raise HTTPException(422, "The recipient must first verify their institute account.")
    if recipient.id == advisor.user.id:
        raise HTTPException(422, "An advisor cannot grant a club role to themselves.")
    now = db.scalar(select(func.clock_timestamp()))
    if body.ends_at <= now:
        raise HTTPException(422, "The appointment must end in the future.")
    starts_at = max(body.starts_at, now)
    key = advisor_key(db, advisor)
    appointment_id = uuid5(
        NAMESPACE_URL,
        f"instichain:appointment:{club_id}:{recipient.id}:{body.position}:"
        f"{starts_at.isoformat()}:{body.ends_at.isoformat()}",
    )
    document = RoleGrantDocument(
        request_id=appointment_id,
        event_type="ROLE_GRANTED",
        object_ids=RoleObjectIds(
            club_id=club_id,
            appointment_id=appointment_id,
            user_id=recipient.id,
        ),
        payload=RolePayload(
            position=body.position,
            prepared_at=now,
            starts_at=starts_at,
            ends_at=body.ends_at,
        ),
        actor_id=advisor.user.id,
        actor_key_id=key.id,
    )
    canonical = rfc8785.dumps(document_dict(document))
    return RoleGrantPreparation(
        document=document,
        signing_payload=base64.b64encode(canonical).decode("ascii"),
    )


@router.post("/clubs/{club_id}/role-grants", status_code=201, response_model=RoleGrantResponse)
def grant_role(
    club_id: UUID,
    body: RoleGrantSubmission,
    advisor: ClubAdvisor,
    db: Database,
    settings: Configuration,
):
    document = body.document
    objects = document.object_ids
    payload = document.payload
    key = advisor_key(db, advisor)
    if (objects.club_id != club_id or document.actor_id != advisor.user.id
            or document.actor_key_id != key.id or document.request_id != objects.appointment_id):
        raise HTTPException(422, "The signed role grant does not match this advisor and club.")
    expected_id = uuid5(
        NAMESPACE_URL,
        f"instichain:appointment:{club_id}:{objects.user_id}:{payload.position}:"
        f"{payload.starts_at.isoformat()}:{payload.ends_at.isoformat()}",
    )
    if expected_id != document.request_id:
        raise HTTPException(422, "The signed role grant has an invalid appointment ID.")

    now = db.scalar(select(func.clock_timestamp()))
    if (payload.prepared_at > now or payload.prepared_at < now - timedelta(minutes=10)
            or payload.starts_at < payload.prepared_at or payload.ends_at <= now):
        raise HTTPException(422, "The signed role grant has expired or contains an invalid term.")
    recipient = db.get(User, objects.user_id)
    if recipient is None or recipient.email_verified_at is None:
        raise HTTPException(422, "The recipient must have a verified institute account.")
    if recipient.id == advisor.user.id:
        raise HTTPException(422, "An advisor cannot grant a club role to themselves.")

    signature = base64.b64decode(body.signature)
    action = document_dict(document)
    try:
        Ed25519PublicKey.from_public_bytes(key.public_key).verify(signature, rfc8785.dumps(action))
    except (InvalidSignature, ValueError):
        raise HTTPException(422, "The advisor signature is invalid.") from None

    existing = db.get(Appointment, document.request_id)
    if existing is not None:
        if (existing.club_id != club_id or existing.user_id != recipient.id
                or existing.position != payload.position or existing.starts_at != payload.starts_at
                or existing.ends_at != payload.ends_at):
            raise HTTPException(409, "This appointment ID is already in use.")
        return role_grant_response(existing)

    overlap = select(Appointment.id).where(
        Appointment.club_id == club_id,
        Appointment.position == payload.position,
        Appointment.revoked_at.is_(None),
        Appointment.starts_at < payload.ends_at,
        Appointment.ends_at > payload.starts_at,
    )
    if payload.position == "office_bearer":
        overlap = overlap.where(Appointment.user_id == recipient.id)
    if db.scalar(overlap):
        raise HTTPException(409, "This role overlaps an existing appointment.")

    try:
        event = append_signed_event(
            db, key, signature, settings, document.request_id, document.event_type,
            action["object_ids"], action["payload"], "advisor",
        )
    except FabricUnavailable:
        raise HTTPException(503, "The Fabric ledger is unavailable; no role was granted.") from None
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from None
    appointment = Appointment(
        id=document.request_id,
        club_id=club_id,
        user_id=recipient.id,
        position=payload.position,
        granted_by_id=advisor.user.id,
        advisor_key_id=key.id,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        grant_transaction_id=event.request_id,
    )
    db.add(appointment)
    db.commit()
    return role_grant_response(appointment)

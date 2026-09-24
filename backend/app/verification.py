import hashlib
import secrets
import smtplib
from datetime import timedelta
from typing import Annotated

from email_validator import EmailNotValidError, validate_email
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import func, select, text, update
from sqlalchemy.orm import Session

from app.config import Settings
from app.database import get_session
from app.email_delivery import send_verification_email
from app.models import EmailVerification, User

router = APIRouter(prefix="/api/auth/email", tags=["email verification"])
REQUEST_MESSAGE = "If this address needs verification, a link will be sent. Check your inbox."
INVALID_MESSAGE = "This verification link is invalid or expired. Request a new one."


def normalize_institute_email(value: str) -> str:
    if not value.isascii():
        raise ValueError("Enter a valid institute email address using ASCII characters")
    try:
        email = validate_email(value.strip(), check_deliverability=False, allow_smtputf8=False).normalized.lower()
    except EmailNotValidError as exc:
        raise ValueError("Enter a valid institute email address") from exc
    domain = email.rsplit("@", 1)[1]
    if domain != "iitm.ac.in" and not domain.endswith(".iitm.ac.in"):
        raise ValueError("Use an iitm.ac.in email address or one of its subdomains")
    return email


class VerificationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(max_length=254)

    @field_validator("email")
    @classmethod
    def validate_email(cls, value: str) -> str:
        return normalize_institute_email(value)


class VerificationConfirm(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    token: str = Field(pattern=r"^[A-Za-z0-9_-]{43}$", max_length=43)
    display_name: str = Field(min_length=1, max_length=200)


class MessageResponse(BaseModel):
    message: str


def verification_settings() -> Settings:
    return Settings()


def lock_email(session: Session, email: str) -> None:
    # Requests and confirmations for the same email serialize across workers.
    key = int.from_bytes(hashlib.sha256(email.encode()).digest()[:8], signed=True)
    session.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": key})


@router.post("/request", status_code=202, response_model=MessageResponse)
def request_verification(
    body: VerificationRequest, response: Response,
    session: Annotated[Session, Depends(get_session)],
    settings: Annotated[Settings, Depends(verification_settings)],
) -> MessageResponse:
    response.headers["Cache-Control"] = "no-store"
    with session.begin():
        lock_email(session, body.email)
        now = session.scalar(select(func.clock_timestamp()))
        verified = session.scalar(select(User.id).where(User.email == body.email, User.email_verified_at.is_not(None)))
        recent = session.scalars(select(EmailVerification.created_at).where(
            EmailVerification.email == body.email,
            EmailVerification.created_at > now - timedelta(days=1),
        ).order_by(EmailVerification.created_at.desc())).all()
        if verified or len(recent) >= 5 or (recent and recent[0] > now - timedelta(seconds=60)):
            return MessageResponse(message=REQUEST_MESSAGE)
        token = secrets.token_urlsafe(32)
        session.execute(update(EmailVerification).where(
            EmailVerification.email == body.email,
            EmailVerification.consumed_at.is_(None), EmailVerification.invalidated_at.is_(None),
        ).values(invalidated_at=now))
        session.add(EmailVerification(
            email=body.email, token_hash=hashlib.sha256(token.encode()).hexdigest(),
            created_at=now, expires_at=now + timedelta(minutes=settings.verification_ttl_minutes),
        ))
        session.flush()
        try:
            send_verification_email(body.email, token, settings)
        except (OSError, smtplib.SMTPException):
            # Rolling back preserves the previous token and resend allowance.
            raise HTTPException(503, "Email delivery is unavailable. Please try again later.", headers={"Cache-Control": "no-store"}) from None
    return MessageResponse(message=REQUEST_MESSAGE)


@router.post("/confirm", response_model=MessageResponse)
def confirm_verification(
    body: VerificationConfirm, response: Response,
    session: Annotated[Session, Depends(get_session)],
) -> MessageResponse:
    response.headers["Cache-Control"] = "no-store"
    digest = hashlib.sha256(body.token.encode()).hexdigest()
    with session.begin():
        email = session.scalar(select(EmailVerification.email).where(EmailVerification.token_hash == digest))
        if email is None:
            raise HTTPException(400, INVALID_MESSAGE, headers={"Cache-Control": "no-store"})
        lock_email(session, email)
        record = session.scalar(select(EmailVerification).where(EmailVerification.token_hash == digest).with_for_update())
        now = session.scalar(select(func.clock_timestamp()))
        if record is None or record.consumed_at or record.invalidated_at or record.expires_at <= now:
            raise HTTPException(400, INVALID_MESSAGE, headers={"Cache-Control": "no-store"})
        user = session.scalar(select(User).where(User.email == email).with_for_update())
        if user is None:
            session.add(User(email=email, display_name=body.display_name, email_verified_at=now))
        elif user.email_verified_at is None:
            user.email_verified_at = now
            user.display_name = body.display_name
        record.consumed_at = now
        session.execute(update(EmailVerification).where(
            EmailVerification.email == email, EmailVerification.id != record.id,
            EmailVerification.consumed_at.is_(None), EmailVerification.invalidated_at.is_(None),
        ).values(invalidated_at=now))
    return MessageResponse(message="Your institute email is verified.")

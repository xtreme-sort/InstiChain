from dataclasses import dataclass
from typing import Annotated, Callable
from urllib.parse import urlsplit
from uuid import UUID

from fastapi import Depends, HTTPException, Request, Response
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.authority import lock_ledger
from app.config import Settings
from app.database import get_session
from app.models import Appointment, Club, User
from app.session import current_user

ADVISOR = "faculty_advisor"
CLUB_ROLES = frozenset({ADVISOR, "club_head", "office_bearer"})


def permission_settings() -> Settings:
    return Settings()


@dataclass(frozen=True)
class AdvisorAuthority:
    user: User
    appointments: tuple[Appointment, ...]


@dataclass(frozen=True)
class ClubAuthority:
    user: User
    club: Club
    appointment: Appointment


def active_appointments(
    db: Session,
    user_id: UUID,
    positions: tuple[str, ...],
    club_id: UUID | None = None,
) -> tuple[Appointment, ...]:
    now = db.scalar(select(func.clock_timestamp()))
    query = select(Appointment).where(
        Appointment.user_id == user_id,
        Appointment.position.in_(positions),
        Appointment.revoked_at.is_(None),
        Appointment.starts_at <= now,
        Appointment.ends_at > now,
    )
    if club_id is not None:
        query = query.where(Appointment.club_id == club_id)
    return tuple(db.scalars(query.order_by(Appointment.starts_at, Appointment.id)).all())


def require_advisor(
    response: Response,
    user: Annotated[User, Depends(current_user)],
    db: Annotated[Session, Depends(get_session)],
) -> AdvisorAuthority:
    response.headers["Cache-Control"] = "no-store"
    if user.email_verified_at is None:
        raise HTTPException(403, "An active faculty advisor appointment is required.")
    appointments = active_appointments(db, user.id, (ADVISOR,))
    if not appointments:
        raise HTTPException(403, "An active faculty advisor appointment is required.")
    return AdvisorAuthority(user=user, appointments=appointments)


def require_club_role(*positions: str) -> Callable[..., ClubAuthority]:
    if not positions or not set(positions).issubset(CLUB_ROLES):
        raise ValueError("At least one valid club role is required")

    def dependency(
        club_id: UUID,
        request: Request,
        response: Response,
        user: Annotated[User, Depends(current_user)],
        db: Annotated[Session, Depends(get_session)],
        settings: Annotated[Settings, Depends(permission_settings)],
    ) -> ClubAuthority:
        response.headers["Cache-Control"] = "no-store"
        if request.method != "GET":
            origin = urlsplit(str(settings.public_app_url))
            if request.headers.get("origin") != f"{origin.scheme}://{origin.netloc}":
                raise HTTPException(403, "A same-origin request is required.")
            lock_ledger(db)
        club = db.get(Club, club_id)
        if club is None:
            raise HTTPException(404, "Club not found.")
        if user.email_verified_at is None:
            raise HTTPException(403, "An active role for this club is required.")
        appointments = active_appointments(db, user.id, positions, club_id)
        if not appointments:
            raise HTTPException(403, "An active role for this club is required.")
        return ClubAuthority(user=user, club=club, appointment=appointments[0])

    return dependency


require_club_advisor = require_club_role(ADVISOR)

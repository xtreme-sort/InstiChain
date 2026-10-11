"""Read-only verification API backed directly by Hyperledger Fabric."""

from typing import Annotated, Literal
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import func, literal, select, union_all
from sqlalchemy.orm import Session

from app.config import Settings
from app.database import get_session
from app.models import Appointment, Credential

router = APIRouter(prefix="/api/ledger", tags=["ledger"])


def ledger_settings() -> Settings:
    return Settings()


Configuration = Annotated[Settings, Depends(ledger_settings)]
Database = Annotated[Session, Depends(get_session)]


def query_fabric(settings: Settings, path: str) -> object:
    token = settings.fabric_bridge_token
    if token is None:
        raise HTTPException(503, "The Fabric ledger is not configured.")
    try:
        response = httpx.get(
            str(settings.fabric_bridge_url).rstrip("/") + path,
            headers={"Authorization": f"Bearer {token.get_secret_value()}"},
            timeout=10,
            follow_redirects=False,
        )
        response.raise_for_status()
        return response.json()
    except (httpx.HTTPError, ValueError, OSError):
        raise HTTPException(503, "The Fabric ledger is unavailable.") from None


@router.get("/health")
def fabric_health(settings: Configuration):
    return query_fabric(settings, "/health")


@router.get("/events")
def fabric_events(settings: Configuration, start: int = Query(default=1, ge=1),
                  limit: int = Query(default=50, ge=1, le=200)):
    return query_fabric(settings, f"/events?start={start}&limit={limit}")


@router.get("/events/{request_id}")
def fabric_event(request_id: UUID, settings: Configuration):
    return query_fabric(settings, f"/events/{request_id}")


@router.get("/fabric-revocations")
def fabric_revocations(settings: Configuration):
    """The on-chain revocation index, for auditing the local projection against Fabric."""
    return query_fabric(settings, "/revocations")


def revocation_rows(subject: str | None):
    """Revoked appointments and credentials as one ordered projection."""
    appointments = select(
        literal("appointment").label("subject_type"),
        Appointment.id.label("subject_id"),
        Appointment.revoked_at.label("revoked_at"),
        Appointment.revocation_transaction_id.label("revocation_transaction_id"),
    ).where(Appointment.revoked_at.is_not(None))
    credentials = select(
        literal("credential").label("subject_type"),
        Credential.id.label("subject_id"),
        Credential.revoked_at.label("revoked_at"),
        Credential.revocation_transaction_id.label("revocation_transaction_id"),
    ).where(Credential.revoked_at.is_not(None))
    if subject == "appointment":
        return appointments
    if subject == "credential":
        return credentials
    return union_all(appointments, credentials)


@router.get("/revocations")
def revocations(db: Database, response: Response,
                subject: Literal["appointment", "credential"] | None = None,
                limit: int = Query(default=100, ge=1, le=500),
                offset: int = Query(default=0, ge=0)):
    """Current revocation list for verifiers; an absent entry is not proof of validity."""
    response.headers["Cache-Control"] = "no-store"
    rows = revocation_rows(subject).subquery()
    now = db.scalar(select(func.clock_timestamp()))
    total = db.scalar(select(func.count()).select_from(rows))
    page = db.execute(
        select(rows).order_by(rows.c.revoked_at.desc(), rows.c.subject_id)
        .limit(limit).offset(offset)
    ).all()
    return {
        "checked_at": now,
        "total": total,
        "limit": limit,
        "offset": offset,
        "revocations": [{
            "subject_type": row.subject_type,
            "subject_id": str(row.subject_id),
            "revoked_at": row.revoked_at,
            "revocation_transaction_id": str(row.revocation_transaction_id),
        } for row in page],
    }


@router.get("/revocations/{subject_type}/{subject_id}")
def revocation_status(subject_type: Literal["appointment", "credential"], subject_id: UUID,
                      db: Database, response: Response):
    """Live revocation check for one subject, as an offline signature cannot prove this."""
    response.headers["Cache-Control"] = "no-store"
    rows = revocation_rows(subject_type).subquery()
    now = db.scalar(select(func.clock_timestamp()))
    row = db.execute(select(rows).where(rows.c.subject_id == subject_id)).first()
    return {
        "subject_type": subject_type,
        "subject_id": str(subject_id),
        "checked_at": now,
        "revoked": row is not None,
        "revoked_at": row.revoked_at if row else None,
        "revocation_transaction_id": str(row.revocation_transaction_id) if row else None,
    }

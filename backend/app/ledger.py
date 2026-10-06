"""Read-only verification API backed directly by Hyperledger Fabric."""

from typing import Annotated
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query

from app.config import Settings

router = APIRouter(prefix="/api/ledger", tags=["ledger"])


def ledger_settings() -> Settings:
    return Settings()


Configuration = Annotated[Settings, Depends(ledger_settings)]


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

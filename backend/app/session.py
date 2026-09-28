import hashlib
import secrets
from datetime import timedelta
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session as DbSession

from app.config import Settings
from app.database import get_session
from app.models import Session, User

router = APIRouter(prefix="/api/auth", tags=["session"])
SESSION_COOKIE = "instichain_session"
UNAUTHORIZED_MESSAGE = "Sign in to continue."


def session_settings() -> Settings:
    return Settings()


def cookie_kwargs(settings: Settings) -> dict:
    secure = str(settings.public_app_url).startswith("https")
    return {"httponly": True, "samesite": "lax", "secure": secure, "path": "/"}


def create_session(db: DbSession, user_id: UUID, settings: Settings) -> str:
    token = secrets.token_urlsafe(32)
    now = db.scalar(select(func.clock_timestamp()))
    db.add(Session(
        user_id=user_id, token_hash=hashlib.sha256(token.encode()).hexdigest(),
        created_at=now, expires_at=now + timedelta(days=settings.session_ttl_days),
    ))
    return token


class UserResponse(BaseModel):
    id: str
    email: str
    display_name: str


def current_user(
    request: Request,
    db: Annotated[DbSession, Depends(get_session)],
) -> User:
    token = request.cookies.get(SESSION_COOKIE)
    if not token:
        raise HTTPException(401, UNAUTHORIZED_MESSAGE, headers={"Cache-Control": "no-store"})
    digest = hashlib.sha256(token.encode()).hexdigest()
    row = db.execute(
        select(Session, User)
        .join(User, User.id == Session.user_id)
        .where(Session.token_hash == digest)
    ).first()
    if row is None:
        raise HTTPException(401, UNAUTHORIZED_MESSAGE, headers={"Cache-Control": "no-store"})
    record, user = row
    now = db.scalar(select(func.clock_timestamp()))
    if record.revoked_at is not None or record.expires_at <= now:
        raise HTTPException(401, UNAUTHORIZED_MESSAGE, headers={"Cache-Control": "no-store"})
    return user


@router.get("/session", response_model=UserResponse)
def read_session(
    response: Response,
    user: Annotated[User, Depends(current_user)],
) -> UserResponse:
    response.headers["Cache-Control"] = "no-store"
    return UserResponse(id=str(user.id), email=user.email, display_name=user.display_name)


@router.post("/logout", status_code=204)
def logout(
    request: Request, response: Response,
    db: Annotated[DbSession, Depends(get_session)],
) -> None:
    response.headers["Cache-Control"] = "no-store"
    token = request.cookies.get(SESSION_COOKIE)
    if token:
        digest = hashlib.sha256(token.encode()).hexdigest()
        with db.begin():
            now = db.scalar(select(func.clock_timestamp()))
            db.execute(update(Session).where(
                Session.token_hash == digest, Session.revoked_at.is_(None),
            ).values(revoked_at=now))
    response.delete_cookie(SESSION_COOKIE, path="/")

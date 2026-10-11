from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_session
from app.models import Competition, User
from app.permissions import (
    ClubAuthority,
    require_club_officer,
    require_club_record_viewer,
)

router = APIRouter(prefix="/api/clubs/{club_id}/competitions", tags=["competitions"])

Database = Annotated[Session, Depends(get_session)]
ClubOfficer = Annotated[ClubAuthority, Depends(require_club_officer)]
ClubRecordViewer = Annotated[ClubAuthority, Depends(require_club_record_viewer)]


class CompetitionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    title: str = Field(min_length=1, max_length=250)
    description: str | None = Field(default=None, max_length=5000)
    starts_at: AwareDatetime
    ends_at: AwareDatetime | None = None

    @model_validator(mode="after")
    def date_order(self):
        if self.ends_at is not None and self.ends_at <= self.starts_at:
            raise ValueError("End time must be after start time")
        return self


class CompetitionResponse(BaseModel):
    id: UUID
    club_id: UUID
    title: str
    description: str | None
    starts_at: AwareDatetime
    ends_at: AwareDatetime | None
    created_by_id: UUID
    created_by_name: str
    created_at: AwareDatetime


def competition_response(competition: Competition, creator: User) -> CompetitionResponse:
    return CompetitionResponse(
        id=competition.id,
        club_id=competition.club_id,
        title=competition.title,
        description=competition.description,
        starts_at=competition.starts_at,
        ends_at=competition.ends_at,
        created_by_id=competition.created_by_id,
        created_by_name=creator.display_name,
        created_at=competition.created_at,
    )


@router.get("", response_model=list[CompetitionResponse])
def list_competitions(club_id: UUID, viewer: ClubRecordViewer, db: Database):
    rows = db.execute(
        select(Competition, User)
        .join(User, User.id == Competition.created_by_id)
        .where(Competition.club_id == club_id)
        .order_by(Competition.starts_at.desc(), Competition.id)
    ).all()
    return [competition_response(competition, creator) for competition, creator in rows]


@router.get("/{competition_id}", response_model=CompetitionResponse)
def read_competition(
    club_id: UUID,
    competition_id: UUID,
    viewer: ClubRecordViewer,
    db: Database,
):
    row = db.execute(
        select(Competition, User)
        .join(User, User.id == Competition.created_by_id)
        .where(Competition.id == competition_id, Competition.club_id == club_id)
    ).first()
    if row is None:
        raise HTTPException(404, "Competition not found.")
    return competition_response(*row)


@router.post("", status_code=201, response_model=CompetitionResponse)
def create_competition(
    club_id: UUID,
    body: CompetitionRequest,
    officer: ClubOfficer,
    db: Database,
):
    competition = Competition(
        club_id=club_id,
        title=body.title,
        description=body.description,
        starts_at=body.starts_at,
        ends_at=body.ends_at,
        created_by_id=officer.user.id,
    )
    db.add(competition)
    db.commit()
    db.refresh(competition)
    return competition_response(competition, officer.user)

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import AwareDatetime, BaseModel

from app.permissions import (
    AdvisorAuthority,
    ClubAuthority,
    require_advisor,
    require_club_advisor,
)

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

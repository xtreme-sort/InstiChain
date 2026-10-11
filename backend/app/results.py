from typing import Annotated
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from fastapi import APIRouter, Depends, HTTPException
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.database import get_session
from app.models import (
    Competition,
    CompetitionResult,
    ResultRecipient,
    ResultSubmission,
    User,
)
from app.permissions import (
    ClubAuthority,
    require_club_officer,
    require_club_record_viewer,
)
from app.verification import normalize_institute_email

router = APIRouter(
    prefix="/api/clubs/{club_id}/competitions/{competition_id}/results",
    tags=["competition results"],
)

Database = Annotated[Session, Depends(get_session)]
ClubOfficer = Annotated[ClubAuthority, Depends(require_club_officer)]
ClubRecordViewer = Annotated[ClubAuthority, Depends(require_club_record_viewer)]


class ResultSubmissionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    award: str = Field(min_length=1, max_length=250)
    proof_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    recipient_emails: list[str] = Field(min_length=1, max_length=100)

    @field_validator("recipient_emails")
    @classmethod
    def verified_email_shape(cls, values: list[str]) -> list[str]:
        normalized = [normalize_institute_email(value) for value in values]
        if len(set(normalized)) != len(normalized):
            raise ValueError("Recipient emails must be unique")
        return normalized


class ResultRecipientResponse(BaseModel):
    id: UUID
    email: str
    display_name: str


class ResultSubmissionResponse(BaseModel):
    id: UUID
    result_id: UUID
    competition_id: UUID
    version: int
    award: str
    proof_digest: str
    submitted_by_id: UUID
    submitted_by_name: str
    submitted_at: AwareDatetime
    recipients: list[ResultRecipientResponse]


def competition_in_club(db: Session, club_id: UUID, competition_id: UUID) -> Competition:
    competition = db.get(Competition, competition_id)
    if competition is None or competition.club_id != club_id:
        raise HTTPException(404, "Competition not found.")
    return competition


def verified_recipients(db: Session, emails: list[str]) -> list[User]:
    users = db.scalars(select(User).where(
        User.email.in_(emails),
        User.email_verified_at.is_not(None),
    )).all()
    by_email = {user.email: user for user in users}
    if set(by_email) != set(emails):
        raise HTTPException(422, "Every recipient must have a verified institute account.")
    return [by_email[email] for email in emails]


def submission_response(db: Session, submission: ResultSubmission) -> ResultSubmissionResponse:
    result = db.get(CompetitionResult, submission.result_id)
    submitter = db.get(User, submission.submitted_by_id)
    recipients = db.scalars(
        select(User)
        .join(ResultRecipient, ResultRecipient.recipient_id == User.id)
        .where(ResultRecipient.submission_id == submission.id)
        .order_by(User.email)
    ).all()
    return ResultSubmissionResponse(
        id=submission.id,
        result_id=submission.result_id,
        competition_id=result.competition_id,
        version=submission.version,
        award=submission.award,
        proof_digest=submission.proof_digest,
        submitted_by_id=submission.submitted_by_id,
        submitted_by_name=submitter.display_name,
        submitted_at=submission.submitted_at,
        recipients=[ResultRecipientResponse(
            id=user.id,
            email=user.email,
            display_name=user.display_name,
        ) for user in recipients],
    )


def save_submission(
    db: Session,
    result: CompetitionResult,
    version: int,
    body: ResultSubmissionRequest,
    submitter: User,
) -> ResultSubmissionResponse:
    recipients = verified_recipients(db, body.recipient_emails)
    submission = ResultSubmission(
        id=uuid5(NAMESPACE_URL, f"instichain:result-submission:{result.id}:{version}"),
        result_id=result.id,
        version=version,
        award=body.award,
        proof_digest=body.proof_digest,
        submitted_by_id=submitter.id,
    )
    db.add(submission)
    db.flush()
    db.add_all(ResultRecipient(
        submission_id=submission.id,
        recipient_id=recipient.id,
    ) for recipient in recipients)
    db.commit()
    db.refresh(submission)
    return submission_response(db, submission)


@router.get("", response_model=list[ResultSubmissionResponse])
def list_result_submissions(
    club_id: UUID,
    competition_id: UUID,
    viewer: ClubRecordViewer,
    db: Database,
):
    competition_in_club(db, club_id, competition_id)
    submissions = db.scalars(
        select(ResultSubmission)
        .join(CompetitionResult, CompetitionResult.id == ResultSubmission.result_id)
        .where(CompetitionResult.competition_id == competition_id)
        .order_by(ResultSubmission.submitted_at, ResultSubmission.id)
    ).all()
    return [submission_response(db, submission) for submission in submissions]


@router.get("/{result_id}/versions/{version}", response_model=ResultSubmissionResponse)
def read_result_submission(
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    version: int,
    viewer: ClubRecordViewer,
    db: Database,
):
    competition_in_club(db, club_id, competition_id)
    submission = db.scalar(
        select(ResultSubmission)
        .join(CompetitionResult, CompetitionResult.id == ResultSubmission.result_id)
        .where(
            CompetitionResult.id == result_id,
            CompetitionResult.competition_id == competition_id,
            ResultSubmission.version == version,
        )
    )
    if submission is None:
        raise HTTPException(404, "Result submission not found.")
    return submission_response(db, submission)


@router.post("", status_code=201, response_model=ResultSubmissionResponse)
def create_result_submission(
    club_id: UUID,
    competition_id: UUID,
    body: ResultSubmissionRequest,
    officer: ClubOfficer,
    db: Database,
):
    competition_in_club(db, club_id, competition_id)
    result = CompetitionResult(
        id=uuid4(),
        competition_id=competition_id,
        created_by_id=officer.user.id,
    )
    db.add(result)
    db.flush()
    return save_submission(db, result, 1, body, officer.user)


@router.post("/{result_id}/versions", status_code=201, response_model=ResultSubmissionResponse)
def create_result_version(
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    body: ResultSubmissionRequest,
    officer: ClubOfficer,
    db: Database,
):
    competition_in_club(db, club_id, competition_id)
    result = db.scalar(select(CompetitionResult).where(
        CompetitionResult.id == result_id,
        CompetitionResult.competition_id == competition_id,
    ))
    if result is None:
        raise HTTPException(404, "Competition result not found.")
    version = db.scalar(select(func.max(ResultSubmission.version)).where(
        ResultSubmission.result_id == result_id,
    )) + 1
    return save_submission(db, result, version, body, officer.user)

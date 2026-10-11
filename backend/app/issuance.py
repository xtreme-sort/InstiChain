import base64
import binascii
from typing import Annotated, Literal
from uuid import NAMESPACE_URL, UUID, uuid5

import rfc8785
from fastapi import APIRouter, Depends, HTTPException
from pydantic import AwareDatetime, BaseModel, ConfigDict, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.advisor import advisor_key, document_dict, verify_signature
from app.authority import FabricUnavailable, append_signed_event
from app.config import Settings
from app.database import get_session
from app.models import (
    Competition,
    CompetitionResult,
    Credential,
    ResultRecipient,
    ResultSubmission,
    User,
)
from app.permissions import (
    ClubAuthority,
    permission_settings,
    require_club_advisor,
)

router = APIRouter(
    prefix="/api/clubs/{club_id}/competitions/{competition_id}/results",
    tags=["credential issuance"],
)

Database = Annotated[Session, Depends(get_session)]
Configuration = Annotated[Settings, Depends(permission_settings)]
ClubAdvisor = Annotated[ClubAuthority, Depends(require_club_advisor)]


class CredentialClaim(BaseModel):
    credential_id: UUID
    recipient_id: UUID
    supersedes_id: UUID | None


class IssuanceObjectIds(BaseModel):
    model_config = ConfigDict(extra="forbid")
    club_id: UUID
    competition_id: UUID
    result_id: UUID
    submission_id: UUID
    credential_ids: list[UUID]


class IssuancePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    submission_version: int
    award: str
    proof_digest: str
    competition_title: str
    competition_starts_at: AwareDatetime
    competition_ends_at: AwareDatetime | None
    superseded_credential_ids: list[UUID]
    claims: list[CredentialClaim]


class IssuanceDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: UUID
    event_type: Literal["ACHIEVEMENT_ISSUED"]
    object_ids: IssuanceObjectIds
    payload: IssuancePayload
    actor_id: UUID
    actor_key_id: UUID


class IssuancePreparation(BaseModel):
    document: IssuanceDocument
    signing_payload: str


class IssuanceSubmission(BaseModel):
    model_config = ConfigDict(extra="forbid")
    document: IssuanceDocument
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


class CredentialResponse(BaseModel):
    id: UUID
    recipient_id: UUID
    award: str
    proof_digest: str
    submission_version: int
    supersedes_id: UUID | None


class IssuanceResponse(BaseModel):
    transaction_id: UUID
    issued_at: AwareDatetime
    credentials: list[CredentialResponse]


def frozen_submission(
    db: Session,
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    version: int,
) -> tuple[Competition, ResultSubmission, list[User]]:
    row = db.execute(
        select(Competition, ResultSubmission)
        .join(CompetitionResult, CompetitionResult.competition_id == Competition.id)
        .join(ResultSubmission, ResultSubmission.result_id == CompetitionResult.id)
        .where(
            Competition.club_id == club_id,
            Competition.id == competition_id,
            CompetitionResult.id == result_id,
            ResultSubmission.version == version,
        )
    ).first()
    if row is None:
        raise HTTPException(404, "Result submission not found.")
    competition, submission = row
    recipients = list(db.scalars(
        select(User)
        .join(ResultRecipient, ResultRecipient.recipient_id == User.id)
        .where(ResultRecipient.submission_id == submission.id)
        .order_by(User.id)
    ).all())
    if not recipients:
        raise HTTPException(409, "The result submission has no recipients.")
    return competition, submission, recipients


def previous_credentials(
    db: Session,
    result_id: UUID,
    version: int,
) -> list[Credential]:
    previous_version = db.scalar(select(func.max(Credential.submission_version)).where(
        Credential.result_id == result_id,
        Credential.submission_version < version,
    ))
    if previous_version is None:
        return []
    return list(db.scalars(
        select(Credential)
        .where(
            Credential.result_id == result_id,
            Credential.submission_version == previous_version,
        )
        .order_by(Credential.recipient_id)
    ).all())


def issuance_document(
    db: Session,
    authority: ClubAuthority,
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    version: int,
) -> IssuanceDocument:
    competition, submission, recipients = frozen_submission(
        db, club_id, competition_id, result_id, version,
    )
    key = advisor_key(db, authority)
    previous = previous_credentials(db, result_id, version)
    previous_by_recipient = {credential.recipient_id: credential for credential in previous}
    claims = [CredentialClaim(
        credential_id=uuid5(
            NAMESPACE_URL,
            f"instichain:credential:{submission.id}:{recipient.id}",
        ),
        recipient_id=recipient.id,
        supersedes_id=(previous_by_recipient[recipient.id].id
                       if recipient.id in previous_by_recipient else None),
    ) for recipient in recipients]
    request_id = uuid5(NAMESPACE_URL, f"instichain:achievement-issued:{submission.id}")
    return IssuanceDocument(
        request_id=request_id,
        event_type="ACHIEVEMENT_ISSUED",
        object_ids=IssuanceObjectIds(
            club_id=club_id,
            competition_id=competition_id,
            result_id=result_id,
            submission_id=submission.id,
            credential_ids=[claim.credential_id for claim in claims],
        ),
        payload=IssuancePayload(
            submission_version=version,
            award=submission.award,
            proof_digest=submission.proof_digest,
            competition_title=competition.title,
            competition_starts_at=competition.starts_at,
            competition_ends_at=competition.ends_at,
            superseded_credential_ids=[credential.id for credential in previous],
            claims=claims,
        ),
        actor_id=authority.user.id,
        actor_key_id=key.id,
    )


def credential_response(credential: Credential) -> CredentialResponse:
    return CredentialResponse(
        id=credential.id,
        recipient_id=credential.recipient_id,
        award=credential.award,
        proof_digest=credential.proof_digest,
        submission_version=credential.submission_version,
        supersedes_id=credential.supersedes_id,
    )


def issuance_response(credentials: list[Credential]) -> IssuanceResponse:
    return IssuanceResponse(
        transaction_id=credentials[0].issuance_transaction_id,
        issued_at=credentials[0].issued_at,
        credentials=[credential_response(credential) for credential in credentials],
    )


@router.post(
    "/{result_id}/versions/{version}/issuance/prepare",
    response_model=IssuancePreparation,
)
def prepare_issuance(
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    version: int,
    advisor: ClubAdvisor,
    db: Database,
):
    document = issuance_document(
        db, advisor, club_id, competition_id, result_id, version,
    )
    return IssuancePreparation(
        document=document,
        signing_payload=base64.b64encode(rfc8785.dumps(document_dict(document))).decode("ascii"),
    )


@router.post(
    "/{result_id}/versions/{version}/issuance",
    status_code=201,
    response_model=IssuanceResponse,
)
def issue_credentials(
    club_id: UUID,
    competition_id: UUID,
    result_id: UUID,
    version: int,
    body: IssuanceSubmission,
    advisor: ClubAdvisor,
    db: Database,
    settings: Configuration,
):
    expected = issuance_document(
        db, advisor, club_id, competition_id, result_id, version,
    )
    if body.document != expected:
        raise HTTPException(422, "The signed issuance does not match the frozen result submission.")
    key = advisor_key(db, advisor)
    signature = verify_signature(key, body.document, body.signature)

    existing = list(db.scalars(
        select(Credential)
        .where(
            Credential.result_id == result_id,
            Credential.submission_version == version,
        )
        .order_by(Credential.recipient_id)
    ).all())
    if existing:
        expected_ids = {claim.credential_id for claim in expected.payload.claims}
        if ({credential.id for credential in existing} != expected_ids
                or any(credential.issuance_transaction_id != expected.request_id for credential in existing)):
            raise HTTPException(409, "This result version has a conflicting credential issuance.")
        return issuance_response(existing)

    action = document_dict(expected)
    try:
        event = append_signed_event(
            db, key, signature, settings, expected.request_id, expected.event_type,
            action["object_ids"], action["payload"], "advisor",
        )
    except FabricUnavailable:
        raise HTTPException(503, "The Fabric ledger is unavailable; no credentials were issued.") from None
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from None

    credentials = [Credential(
        id=claim.credential_id,
        competition_id=competition_id,
        recipient_id=claim.recipient_id,
        advisor_id=advisor.user.id,
        award=expected.payload.award,
        result_id=result_id,
        submission_version=version,
        proof_digest=expected.payload.proof_digest,
        issuance_transaction_id=event.request_id,
        issued_at=event.committed_at,
        supersedes_id=claim.supersedes_id,
    ) for claim in expected.payload.claims]
    db.add_all(credentials)
    db.commit()
    return issuance_response(credentials)

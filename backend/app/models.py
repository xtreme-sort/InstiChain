from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import (
    BigInteger, CheckConstraint, DateTime, ForeignKey, Index, Integer,
    LargeBinary, String, Text, UniqueConstraint, func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        CheckConstraint("email = lower(trim(email)) AND email <> ''", name="normalized_email"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    display_name: Mapped[str] = mapped_column(String(200))
    email_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class EmailVerification(Base):
    __tablename__ = "email_verifications"
    __table_args__ = (
        CheckConstraint("email = lower(trim(email)) AND email <> ''", name="normalized_email"),
        CheckConstraint("token_hash ~ '^[0-9a-f]{64}$'", name="token_hash_format"),
        CheckConstraint("expires_at > created_at", name="expiry_order"),
        Index("ix_email_verifications_email_created_at", "email", "created_at"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    email: Mapped[str] = mapped_column(String(254))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    invalidated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Club(Base):
    __tablename__ = "clubs"
    __table_args__ = (
        CheckConstraint("slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'", name="slug_format"),
        CheckConstraint("length(trim(name)) > 0", name="nonempty_name"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    slug: Mapped[str] = mapped_column(String(100), unique=True)
    name: Mapped[str] = mapped_column(String(200))
    description: Mapped[str | None] = mapped_column(Text)
    created_by_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class LedgerEntry(Base):
    __tablename__ = "ledger_entries"
    __table_args__ = (
        CheckConstraint("sequence > 0", name="positive_sequence"),
        CheckConstraint("schema_version > 0", name="positive_schema_version"),
        CheckConstraint("event_type ~ '^[A-Z][A-Z0-9_]*$'", name="event_type_format"),
        CheckConstraint("jsonb_typeof(object_ids) = 'object'", name="object_ids_object"),
        CheckConstraint("jsonb_typeof(payload) = 'object'", name="payload_object"),
        CheckConstraint("octet_length(signature) = 64", name="signature_length"),
        CheckConstraint("entry_hash ~ '^[0-9a-f]{64}$'", name="entry_hash_format"),
        CheckConstraint(
            "(sequence = 1 AND previous_entry_hash IS NULL) OR "
            "(sequence > 1 AND previous_entry_hash IS NOT NULL "
            "AND previous_entry_hash ~ '^[0-9a-f]{64}$')",
            name="previous_hash_format",
        ),
        Index("ix_ledger_entries_event_type_sequence", "event_type", "sequence"),
    )

    # The future serialized writer supplies sequence numbers, not a DB sequence.
    sequence: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=False)
    transaction_id: Mapped[UUID] = mapped_column(unique=True, default=uuid4)
    schema_version: Mapped[int] = mapped_column(Integer, server_default="1")
    event_type: Mapped[str] = mapped_column(String(64))
    object_ids: Mapped[dict] = mapped_column(JSONB)
    payload: Mapped[dict] = mapped_column(JSONB)
    actor_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    # Opaque key reference until the key-registry milestone adds its own table.
    actor_key_id: Mapped[UUID]
    signature: Mapped[bytes] = mapped_column(LargeBinary)
    committed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    previous_entry_hash: Mapped[str | None] = mapped_column(String(64))
    entry_hash: Mapped[str] = mapped_column(String(64), unique=True)


class Appointment(Base):
    __tablename__ = "appointments"
    __table_args__ = (
        CheckConstraint("position IN ('faculty_advisor', 'club_head', 'office_bearer')", name="position"),
        CheckConstraint("ends_at > starts_at", name="term_order"),
        CheckConstraint("revoked_at IS NULL OR revoked_at >= created_at", name="revocation_time"),
        CheckConstraint(
            "(revoked_at IS NULL AND revocation_transaction_id IS NULL) OR "
            "(revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)",
            name="revocation_reference",
        ),
        Index("ix_appointments_club_id_user_id", "club_id", "user_id"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    club_id: Mapped[UUID] = mapped_column(ForeignKey("clubs.id"))
    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    position: Mapped[str] = mapped_column(String(32))
    granted_by_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"))
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    grant_transaction_id: Mapped[UUID] = mapped_column(ForeignKey("ledger_entries.transaction_id"), unique=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_transaction_id: Mapped[UUID | None] = mapped_column(ForeignKey("ledger_entries.transaction_id"), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Competition(Base):
    __tablename__ = "competitions"
    __table_args__ = (
        CheckConstraint("length(trim(title)) > 0", name="nonempty_title"),
        CheckConstraint("ends_at IS NULL OR ends_at > starts_at", name="date_order"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    club_id: Mapped[UUID] = mapped_column(ForeignKey("clubs.id"), index=True)
    title: Mapped[str] = mapped_column(String(250))
    description: Mapped[str | None] = mapped_column(Text)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_by_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Credential(Base):
    __tablename__ = "credentials"
    __table_args__ = (
        CheckConstraint("length(trim(award)) > 0", name="nonempty_award"),
        CheckConstraint("submission_version > 0", name="positive_submission_version"),
        CheckConstraint("proof_digest ~ '^[0-9a-f]{64}$'", name="proof_digest_format"),
        CheckConstraint("revoked_at IS NULL OR revoked_at >= issued_at", name="revocation_time"),
        CheckConstraint(
            "(revoked_at IS NULL AND revocation_transaction_id IS NULL) OR "
            "(revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)",
            name="revocation_reference",
        ),
        CheckConstraint("supersedes_id IS NULL OR supersedes_id <> id", name="no_self_supersession"),
        UniqueConstraint("result_id", "submission_version", "recipient_id", name="uq_credentials_result_version_recipient"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    competition_id: Mapped[UUID] = mapped_column(ForeignKey("competitions.id"), index=True)
    recipient_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    advisor_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    award: Mapped[str] = mapped_column(String(250))
    # Shared by team members; a future submission table will own this reference.
    result_id: Mapped[UUID]
    submission_version: Mapped[int] = mapped_column(Integer)
    proof_digest: Mapped[str] = mapped_column(String(64))
    issuance_transaction_id: Mapped[UUID] = mapped_column(ForeignKey("ledger_entries.transaction_id"), unique=True)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_transaction_id: Mapped[UUID | None] = mapped_column(ForeignKey("ledger_entries.transaction_id"), unique=True)
    supersedes_id: Mapped[UUID | None] = mapped_column(ForeignKey("credentials.id"), unique=True)

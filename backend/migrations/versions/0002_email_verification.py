"""Add expiring, single-use email verification tokens."""

from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "email_verifications",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("email", sa.String(254), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("invalidated_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("email = lower(trim(email)) AND email <> ''", name=op.f("ck_email_verifications_normalized_email")),
        sa.CheckConstraint("token_hash ~ '^[0-9a-f]{64}$'", name=op.f("ck_email_verifications_token_hash_format")),
        sa.CheckConstraint("expires_at > created_at", name=op.f("ck_email_verifications_expiry_order")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_email_verifications")),
        sa.UniqueConstraint("token_hash", name=op.f("uq_email_verifications_token_hash")),
    )
    op.create_index("ix_email_verifications_email_created_at", "email_verifications", ["email", "created_at"])
    op.create_index("ix_email_verifications_expires_at", "email_verifications", ["expires_at"])


def downgrade() -> None:
    op.drop_table("email_verifications")

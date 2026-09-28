"""Add controlled administrator bootstrap and advisor key bindings."""

from alembic import op
import sqlalchemy as sa

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "authority_keys",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("public_key", sa.LargeBinary(), nullable=False),
        sa.Column("purpose", sa.String(32), nullable=False),
        sa.Column("verified_by_id", sa.Uuid(), nullable=False),
        sa.Column("verification_reference", sa.String(500), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("octet_length(public_key) = 32", name=op.f("ck_authority_keys_public_key_length")),
        sa.CheckConstraint("purpose IN ('administrator', 'advisor')", name=op.f("ck_authority_keys_purpose")),
        sa.CheckConstraint("length(trim(verification_reference)) > 0", name=op.f("ck_authority_keys_verification_reference")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_authority_keys")),
        sa.UniqueConstraint("public_key", name=op.f("uq_authority_keys_public_key")),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name=op.f("fk_authority_keys_user_id_users")),
        sa.ForeignKeyConstraint(["verified_by_id"], ["users.id"], name=op.f("fk_authority_keys_verified_by_id_users")),
    )
    op.create_index("ix_authority_keys_user_id", "authority_keys", ["user_id"])
    op.create_table(
        "administrator",
        sa.Column("id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("key_id", sa.Uuid(), nullable=False),
        sa.Column("bootstrap_transaction_id", sa.Uuid(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("id = 1", name=op.f("ck_administrator_singleton")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_administrator")),
        sa.UniqueConstraint("user_id", name=op.f("uq_administrator_user_id")),
        sa.UniqueConstraint("key_id", name=op.f("uq_administrator_key_id")),
        sa.UniqueConstraint("bootstrap_transaction_id", name=op.f("uq_administrator_bootstrap_transaction_id")),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name=op.f("fk_administrator_user_id_users")),
        sa.ForeignKeyConstraint(["key_id"], ["authority_keys.id"], name=op.f("fk_administrator_key_id_authority_keys")),
        sa.ForeignKeyConstraint(["bootstrap_transaction_id"], ["ledger_entries.transaction_id"], name=op.f("fk_administrator_bootstrap_transaction_id_ledger_entries")),
    )
    op.add_column("appointments", sa.Column("advisor_key_id", sa.Uuid(), nullable=True))
    op.add_column("appointments", sa.Column("verification_reference", sa.String(500), nullable=True))
    op.create_foreign_key(op.f("fk_appointments_advisor_key_id_authority_keys"), "appointments", "authority_keys", ["advisor_key_id"], ["id"])


def downgrade() -> None:
    op.drop_constraint(op.f("fk_appointments_advisor_key_id_authority_keys"), "appointments", type_="foreignkey")
    op.drop_column("appointments", "verification_reference")
    op.drop_column("appointments", "advisor_key_id")
    op.drop_table("administrator")
    op.drop_table("authority_keys")

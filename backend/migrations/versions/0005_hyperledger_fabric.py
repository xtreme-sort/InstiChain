"""Use Hyperledger Fabric for new ledger events.

Revision ID: 0005
Revises: 0004
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "fabric_transactions",
        sa.Column("request_id", sa.Uuid(), nullable=False),
        sa.Column("source", sa.String(16), nullable=False),
        sa.Column("sequence", sa.BigInteger(), nullable=True),
        sa.Column("fabric_transaction_id", sa.String(128), nullable=True),
        sa.Column("submitted_transaction_id", sa.String(128), nullable=True),
        sa.Column("channel_name", sa.String(128), nullable=True),
        sa.Column("chaincode_name", sa.String(128), nullable=True),
        sa.Column("block_number", sa.BigInteger(), nullable=True),
        sa.Column("validation_code", sa.Integer(), nullable=True),
        sa.Column("schema_version", sa.Integer(), nullable=False),
        sa.Column("event_type", sa.String(64), nullable=False),
        sa.Column("object_ids", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("actor_id", sa.Uuid(), nullable=False),
        sa.Column("actor_key_id", sa.Uuid(), nullable=False),
        sa.Column("signature", sa.LargeBinary(), nullable=True),
        sa.Column("committed_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("source IN ('fabric', 'legacy')", name=op.f("ck_fabric_transactions_source")),
        sa.CheckConstraint("sequence IS NULL OR sequence > 0", name=op.f("ck_fabric_transactions_positive_sequence")),
        sa.CheckConstraint("schema_version > 0", name=op.f("ck_fabric_transactions_positive_schema_version")),
        sa.CheckConstraint("event_type ~ '^[A-Z][A-Z0-9_]*$'", name=op.f("ck_fabric_transactions_event_type_format")),
        sa.CheckConstraint("jsonb_typeof(object_ids) = 'object'", name=op.f("ck_fabric_transactions_object_ids_object")),
        sa.CheckConstraint("jsonb_typeof(payload) = 'object'", name=op.f("ck_fabric_transactions_payload_object")),
        sa.ForeignKeyConstraint(["actor_id"], ["users.id"], name=op.f("fk_fabric_transactions_actor_id_users")),
        sa.PrimaryKeyConstraint("request_id", name=op.f("pk_fabric_transactions")),
        sa.UniqueConstraint("fabric_transaction_id", name=op.f("uq_fabric_transactions_fabric_transaction_id")),
    )
    op.create_index(op.f("ix_fabric_transactions_actor_id"), "fabric_transactions", ["actor_id"])
    op.create_index("ix_fabric_transactions_event_type_sequence", "fabric_transactions", ["event_type", "sequence"])

    # Preserve the previous database hash chain as legacy audit receipts. It is
    # never written by the application after this revision.
    op.execute("""
        INSERT INTO fabric_transactions (
            request_id, source, sequence, schema_version, event_type, object_ids,
            payload, actor_id, actor_key_id, signature, committed_at
        )
        SELECT transaction_id, 'legacy', sequence, schema_version, event_type,
               object_ids, payload, actor_id, actor_key_id, signature, committed_at
        FROM ledger_entries
    """)

    references = (
        ("administrator", "bootstrap_transaction_id"),
        ("appointments", "grant_transaction_id"),
        ("appointments", "revocation_transaction_id"),
        ("credentials", "issuance_transaction_id"),
        ("credentials", "revocation_transaction_id"),
    )
    for table, column in references:
        op.drop_constraint(op.f(f"fk_{table}_{column}_ledger_entries"), table, type_="foreignkey")
        op.create_foreign_key(
            op.f(f"fk_{table}_{column}_fabric_transactions"), table,
            "fabric_transactions", [column], ["request_id"],
        )


def downgrade() -> None:
    references = (
        ("administrator", "bootstrap_transaction_id"),
        ("appointments", "grant_transaction_id"),
        ("appointments", "revocation_transaction_id"),
        ("credentials", "issuance_transaction_id"),
        ("credentials", "revocation_transaction_id"),
    )
    # Fabric-only projections cannot be represented by the old hash-chain schema.
    op.execute("DELETE FROM appointments WHERE grant_transaction_id IN (SELECT request_id FROM fabric_transactions WHERE source = 'fabric')")
    op.execute("DELETE FROM credentials WHERE issuance_transaction_id IN (SELECT request_id FROM fabric_transactions WHERE source = 'fabric')")
    op.execute("DELETE FROM administrator WHERE bootstrap_transaction_id IN (SELECT request_id FROM fabric_transactions WHERE source = 'fabric')")
    for table, column in references:
        op.drop_constraint(op.f(f"fk_{table}_{column}_fabric_transactions"), table, type_="foreignkey")
        op.create_foreign_key(
            op.f(f"fk_{table}_{column}_ledger_entries"), table,
            "ledger_entries", [column], ["transaction_id"],
        )
    op.drop_index("ix_fabric_transactions_event_type_sequence", table_name="fabric_transactions")
    op.drop_index(op.f("ix_fabric_transactions_actor_id"), table_name="fabric_transactions")
    op.drop_table("fabric_transactions")

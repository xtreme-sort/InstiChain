"""Allow one signed Fabric issuance event to create team credentials.

Revision ID: 0007
Revises: 0006
"""

from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_constraint(
        op.f("uq_credentials_issuance_transaction_id"),
        "credentials",
        type_="unique",
    )
    op.create_index(
        op.f("ix_credentials_issuance_transaction_id"),
        "credentials",
        ["issuance_transaction_id"],
    )


def downgrade() -> None:
    op.drop_index(
        op.f("ix_credentials_issuance_transaction_id"),
        table_name="credentials",
    )
    op.create_unique_constraint(
        op.f("uq_credentials_issuance_transaction_id"),
        "credentials",
        ["issuance_transaction_id"],
    )

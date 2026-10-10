"""Add immutable, versioned competition result submissions.

Revision ID: 0006
Revises: 0005
"""

from alembic import op
import sqlalchemy as sa

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "competition_results",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("competition_id", sa.Uuid(), nullable=False),
        sa.Column("created_by_id", sa.Uuid(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(
            ["competition_id"], ["competitions.id"],
            name=op.f("fk_competition_results_competition_id_competitions"),
        ),
        sa.ForeignKeyConstraint(
            ["created_by_id"], ["users.id"],
            name=op.f("fk_competition_results_created_by_id_users"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_competition_results")),
    )
    op.create_index(
        op.f("ix_competition_results_competition_id"),
        "competition_results", ["competition_id"],
    )
    op.create_table(
        "result_submissions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("result_id", sa.Uuid(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("award", sa.String(250), nullable=False),
        sa.Column("proof_digest", sa.String(64), nullable=False),
        sa.Column("submitted_by_id", sa.Uuid(), nullable=False),
        sa.Column("submitted_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("version > 0", name=op.f("ck_result_submissions_positive_version")),
        sa.CheckConstraint(
            "length(trim(award)) > 0",
            name=op.f("ck_result_submissions_nonempty_award"),
        ),
        sa.CheckConstraint(
            "proof_digest ~ '^[0-9a-f]{64}$'",
            name=op.f("ck_result_submissions_proof_digest_format"),
        ),
        sa.ForeignKeyConstraint(
            ["result_id"], ["competition_results.id"],
            name=op.f("fk_result_submissions_result_id_competition_results"),
        ),
        sa.ForeignKeyConstraint(
            ["submitted_by_id"], ["users.id"],
            name=op.f("fk_result_submissions_submitted_by_id_users"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_result_submissions")),
        sa.UniqueConstraint(
            "result_id", "version",
            name="uq_result_submissions_result_version",
        ),
    )
    op.create_index(
        op.f("ix_result_submissions_result_id"),
        "result_submissions", ["result_id"],
    )
    op.create_table(
        "result_recipients",
        sa.Column("submission_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_id", sa.Uuid(), nullable=False),
        sa.ForeignKeyConstraint(
            ["submission_id"], ["result_submissions.id"],
            name=op.f("fk_result_recipients_submission_id_result_submissions"),
        ),
        sa.ForeignKeyConstraint(
            ["recipient_id"], ["users.id"],
            name=op.f("fk_result_recipients_recipient_id_users"),
        ),
        sa.PrimaryKeyConstraint(
            "submission_id", "recipient_id",
            name=op.f("pk_result_recipients"),
        ),
    )
    op.create_index(
        op.f("ix_result_recipients_recipient_id"),
        "result_recipients", ["recipient_id"],
    )
    op.execute("""
        CREATE FUNCTION reject_result_record_mutation() RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'submitted result versions are immutable';
        END;
        $$ LANGUAGE plpgsql
    """)
    for table in ("competition_results", "result_submissions", "result_recipients"):
        op.execute(f"""
            CREATE TRIGGER {table}_immutable
            BEFORE UPDATE OR DELETE OR TRUNCATE ON {table}
            FOR EACH STATEMENT EXECUTE FUNCTION reject_result_record_mutation()
        """)


def downgrade() -> None:
    op.drop_table("result_recipients")
    op.drop_table("result_submissions")
    op.drop_table("competition_results")
    op.execute("DROP FUNCTION reject_result_record_mutation()")

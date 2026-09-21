"""initial core and ledger schema

Revision ID: 0001
Revises: None
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('users',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('email', sa.String(length=254), nullable=False),
    sa.Column('display_name', sa.String(length=200), nullable=False),
    sa.Column('email_verified_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.CheckConstraint("email = lower(trim(email)) AND email <> ''", name=op.f('ck_users_normalized_email')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_users')),
    sa.UniqueConstraint('email', name=op.f('uq_users_email'))
    )
    op.create_table('clubs',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('slug', sa.String(length=100), nullable=False),
    sa.Column('name', sa.String(length=200), nullable=False),
    sa.Column('description', sa.Text(), nullable=True),
    sa.Column('created_by_id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.CheckConstraint("slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'", name=op.f('ck_clubs_slug_format')),
    sa.CheckConstraint('length(trim(name)) > 0', name=op.f('ck_clubs_nonempty_name')),
    sa.ForeignKeyConstraint(['created_by_id'], ['users.id'], name=op.f('fk_clubs_created_by_id_users')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_clubs')),
    sa.UniqueConstraint('slug', name=op.f('uq_clubs_slug'))
    )
    op.create_table('ledger_entries',
    sa.Column('sequence', sa.BigInteger(), autoincrement=False, nullable=False),
    sa.Column('transaction_id', sa.Uuid(), nullable=False),
    sa.Column('schema_version', sa.Integer(), server_default='1', nullable=False),
    sa.Column('event_type', sa.String(length=64), nullable=False),
    sa.Column('object_ids', postgresql.JSONB(astext_type=sa.Text()), nullable=False),
    sa.Column('payload', postgresql.JSONB(astext_type=sa.Text()), nullable=False),
    sa.Column('actor_id', sa.Uuid(), nullable=False),
    sa.Column('actor_key_id', sa.Uuid(), nullable=False),
    sa.Column('signature', sa.LargeBinary(), nullable=False),
    sa.Column('committed_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.Column('previous_entry_hash', sa.String(length=64), nullable=True),
    sa.Column('entry_hash', sa.String(length=64), nullable=False),
    sa.CheckConstraint("(sequence = 1 AND previous_entry_hash IS NULL) OR (sequence > 1 AND previous_entry_hash IS NOT NULL AND previous_entry_hash ~ '^[0-9a-f]{64}$')", name=op.f('ck_ledger_entries_previous_hash_format')),
    sa.CheckConstraint("entry_hash ~ '^[0-9a-f]{64}$'", name=op.f('ck_ledger_entries_entry_hash_format')),
    sa.CheckConstraint("event_type ~ '^[A-Z][A-Z0-9_]*$'", name=op.f('ck_ledger_entries_event_type_format')),
    sa.CheckConstraint("jsonb_typeof(object_ids) = 'object'", name=op.f('ck_ledger_entries_object_ids_object')),
    sa.CheckConstraint("jsonb_typeof(payload) = 'object'", name=op.f('ck_ledger_entries_payload_object')),
    sa.CheckConstraint('octet_length(signature) = 64', name=op.f('ck_ledger_entries_signature_length')),
    sa.CheckConstraint('schema_version > 0', name=op.f('ck_ledger_entries_positive_schema_version')),
    sa.CheckConstraint('sequence > 0', name=op.f('ck_ledger_entries_positive_sequence')),
    sa.ForeignKeyConstraint(['actor_id'], ['users.id'], name=op.f('fk_ledger_entries_actor_id_users')),
    sa.PrimaryKeyConstraint('sequence', name=op.f('pk_ledger_entries')),
    sa.UniqueConstraint('entry_hash', name=op.f('uq_ledger_entries_entry_hash')),
    sa.UniqueConstraint('transaction_id', name=op.f('uq_ledger_entries_transaction_id'))
    )
    op.create_index(op.f('ix_ledger_entries_actor_id'), 'ledger_entries', ['actor_id'], unique=False)
    op.create_index('ix_ledger_entries_event_type_sequence', 'ledger_entries', ['event_type', 'sequence'], unique=False)
    op.create_table('appointments',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('club_id', sa.Uuid(), nullable=False),
    sa.Column('user_id', sa.Uuid(), nullable=False),
    sa.Column('position', sa.String(length=32), nullable=False),
    sa.Column('granted_by_id', sa.Uuid(), nullable=False),
    sa.Column('starts_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('ends_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('grant_transaction_id', sa.Uuid(), nullable=False),
    sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('revocation_transaction_id', sa.Uuid(), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.CheckConstraint("position IN ('faculty_advisor', 'club_head', 'office_bearer')", name=op.f('ck_appointments_position')),
    sa.CheckConstraint('(revoked_at IS NULL AND revocation_transaction_id IS NULL) OR (revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)', name=op.f('ck_appointments_revocation_reference')),
    sa.CheckConstraint('ends_at > starts_at', name=op.f('ck_appointments_term_order')),
    sa.CheckConstraint('revoked_at IS NULL OR revoked_at >= created_at', name=op.f('ck_appointments_revocation_time')),
    sa.ForeignKeyConstraint(['club_id'], ['clubs.id'], name=op.f('fk_appointments_club_id_clubs')),
    sa.ForeignKeyConstraint(['grant_transaction_id'], ['ledger_entries.transaction_id'], name=op.f('fk_appointments_grant_transaction_id_ledger_entries')),
    sa.ForeignKeyConstraint(['granted_by_id'], ['users.id'], name=op.f('fk_appointments_granted_by_id_users')),
    sa.ForeignKeyConstraint(['revocation_transaction_id'], ['ledger_entries.transaction_id'], name=op.f('fk_appointments_revocation_transaction_id_ledger_entries')),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_appointments_user_id_users')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_appointments')),
    sa.UniqueConstraint('grant_transaction_id', name=op.f('uq_appointments_grant_transaction_id')),
    sa.UniqueConstraint('revocation_transaction_id', name=op.f('uq_appointments_revocation_transaction_id'))
    )
    op.create_index('ix_appointments_club_id_user_id', 'appointments', ['club_id', 'user_id'], unique=False)
    op.create_index(op.f('ix_appointments_user_id'), 'appointments', ['user_id'], unique=False)
    op.create_table('competitions',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('club_id', sa.Uuid(), nullable=False),
    sa.Column('title', sa.String(length=250), nullable=False),
    sa.Column('description', sa.Text(), nullable=True),
    sa.Column('starts_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('ends_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('created_by_id', sa.Uuid(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.CheckConstraint('ends_at IS NULL OR ends_at > starts_at', name=op.f('ck_competitions_date_order')),
    sa.CheckConstraint('length(trim(title)) > 0', name=op.f('ck_competitions_nonempty_title')),
    sa.ForeignKeyConstraint(['club_id'], ['clubs.id'], name=op.f('fk_competitions_club_id_clubs')),
    sa.ForeignKeyConstraint(['created_by_id'], ['users.id'], name=op.f('fk_competitions_created_by_id_users')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_competitions'))
    )
    op.create_index(op.f('ix_competitions_club_id'), 'competitions', ['club_id'], unique=False)
    op.create_table('credentials',
    sa.Column('id', sa.Uuid(), nullable=False),
    sa.Column('competition_id', sa.Uuid(), nullable=False),
    sa.Column('recipient_id', sa.Uuid(), nullable=False),
    sa.Column('advisor_id', sa.Uuid(), nullable=False),
    sa.Column('award', sa.String(length=250), nullable=False),
    sa.Column('result_id', sa.Uuid(), nullable=False),
    sa.Column('submission_version', sa.Integer(), nullable=False),
    sa.Column('proof_digest', sa.String(length=64), nullable=False),
    sa.Column('issuance_transaction_id', sa.Uuid(), nullable=False),
    sa.Column('issued_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('revocation_transaction_id', sa.Uuid(), nullable=True),
    sa.Column('supersedes_id', sa.Uuid(), nullable=True),
    sa.CheckConstraint("proof_digest ~ '^[0-9a-f]{64}$'", name=op.f('ck_credentials_proof_digest_format')),
    sa.CheckConstraint('(revoked_at IS NULL AND revocation_transaction_id IS NULL) OR (revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)', name=op.f('ck_credentials_revocation_reference')),
    sa.CheckConstraint('length(trim(award)) > 0', name=op.f('ck_credentials_nonempty_award')),
    sa.CheckConstraint('revoked_at IS NULL OR revoked_at >= issued_at', name=op.f('ck_credentials_revocation_time')),
    sa.CheckConstraint('submission_version > 0', name=op.f('ck_credentials_positive_submission_version')),
    sa.CheckConstraint('supersedes_id IS NULL OR supersedes_id <> id', name=op.f('ck_credentials_no_self_supersession')),
    sa.ForeignKeyConstraint(['advisor_id'], ['users.id'], name=op.f('fk_credentials_advisor_id_users')),
    sa.ForeignKeyConstraint(['competition_id'], ['competitions.id'], name=op.f('fk_credentials_competition_id_competitions')),
    sa.ForeignKeyConstraint(['issuance_transaction_id'], ['ledger_entries.transaction_id'], name=op.f('fk_credentials_issuance_transaction_id_ledger_entries')),
    sa.ForeignKeyConstraint(['recipient_id'], ['users.id'], name=op.f('fk_credentials_recipient_id_users')),
    sa.ForeignKeyConstraint(['revocation_transaction_id'], ['ledger_entries.transaction_id'], name=op.f('fk_credentials_revocation_transaction_id_ledger_entries')),
    sa.ForeignKeyConstraint(['supersedes_id'], ['credentials.id'], name=op.f('fk_credentials_supersedes_id_credentials')),
    sa.PrimaryKeyConstraint('id', name=op.f('pk_credentials')),
    sa.UniqueConstraint('issuance_transaction_id', name=op.f('uq_credentials_issuance_transaction_id')),
    sa.UniqueConstraint('result_id', 'submission_version', 'recipient_id', name='uq_credentials_result_version_recipient'),
    sa.UniqueConstraint('revocation_transaction_id', name=op.f('uq_credentials_revocation_transaction_id')),
    sa.UniqueConstraint('supersedes_id', name=op.f('uq_credentials_supersedes_id'))
    )
    op.create_index(op.f('ix_credentials_advisor_id'), 'credentials', ['advisor_id'], unique=False)
    op.create_index(op.f('ix_credentials_competition_id'), 'credentials', ['competition_id'], unique=False)
    op.create_index(op.f('ix_credentials_recipient_id'), 'credentials', ['recipient_id'], unique=False)
    op.execute("""
        CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'ledger_entries is append-only';
        END;
        $$
    """)
    op.execute("""
        CREATE TRIGGER ledger_entries_immutable
        BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
        FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation()
    """)


def downgrade() -> None:
    op.drop_index(op.f('ix_credentials_recipient_id'), table_name='credentials')
    op.drop_index(op.f('ix_credentials_competition_id'), table_name='credentials')
    op.drop_index(op.f('ix_credentials_advisor_id'), table_name='credentials')
    op.drop_table('credentials')
    op.drop_index(op.f('ix_competitions_club_id'), table_name='competitions')
    op.drop_table('competitions')
    op.drop_index(op.f('ix_appointments_user_id'), table_name='appointments')
    op.drop_index('ix_appointments_club_id_user_id', table_name='appointments')
    op.drop_table('appointments')
    op.drop_index('ix_ledger_entries_event_type_sequence', table_name='ledger_entries')
    op.drop_index(op.f('ix_ledger_entries_actor_id'), table_name='ledger_entries')
    op.drop_table('ledger_entries')
    op.execute('DROP FUNCTION reject_ledger_mutation()')
    op.drop_table('clubs')
    op.drop_table('users')

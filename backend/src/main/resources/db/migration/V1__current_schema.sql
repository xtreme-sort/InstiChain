CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_users_normalized_email CHECK (email = lower(trim(email)) AND email <> '')
);

CREATE TABLE clubs (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_by_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_clubs_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_clubs_nonempty_name CHECK (length(trim(name)) > 0)
);

CREATE TABLE ledger_entries (
    sequence BIGINT PRIMARY KEY,
    transaction_id UUID NOT NULL UNIQUE,
    schema_version INTEGER NOT NULL DEFAULT 1,
    event_type VARCHAR(64) NOT NULL,
    object_ids JSONB NOT NULL,
    payload JSONB NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id),
    actor_key_id UUID NOT NULL,
    signature BYTEA NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    previous_entry_hash VARCHAR(64),
    entry_hash VARCHAR(64) NOT NULL UNIQUE,
    CONSTRAINT ck_ledger_entries_positive_sequence CHECK (sequence > 0),
    CONSTRAINT ck_ledger_entries_positive_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_ledger_entries_event_type_format CHECK (event_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_ledger_entries_object_ids_object CHECK (jsonb_typeof(object_ids) = 'object'),
    CONSTRAINT ck_ledger_entries_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_ledger_entries_signature_length CHECK (octet_length(signature) = 64),
    CONSTRAINT ck_ledger_entries_entry_hash_format CHECK (entry_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ledger_entries_previous_hash_format CHECK (
        (sequence = 1 AND previous_entry_hash IS NULL) OR
        (sequence > 1 AND previous_entry_hash ~ '^[0-9a-f]{64}$')
    )
);
CREATE INDEX ix_ledger_entries_actor_id ON ledger_entries(actor_id);
CREATE INDEX ix_ledger_entries_event_type_sequence ON ledger_entries(event_type, sequence);

CREATE TABLE email_verifications (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_email_verifications_normalized_email CHECK (email = lower(trim(email)) AND email <> ''),
    CONSTRAINT ck_email_verifications_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_email_verifications_expiry_order CHECK (expires_at > created_at)
);
CREATE INDEX ix_email_verifications_email_created_at ON email_verifications(email, created_at);
CREATE INDEX ix_email_verifications_expires_at ON email_verifications(expires_at);

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_sessions_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sessions_expiry_order CHECK (expires_at > created_at)
);
CREATE INDEX ix_sessions_user_id ON sessions(user_id);
CREATE INDEX ix_sessions_expires_at ON sessions(expires_at);

CREATE TABLE authority_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    public_key BYTEA NOT NULL UNIQUE,
    purpose VARCHAR(32) NOT NULL,
    verified_by_id UUID NOT NULL REFERENCES users(id),
    verification_reference VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_authority_keys_public_key_length CHECK (octet_length(public_key) = 32),
    CONSTRAINT ck_authority_keys_purpose CHECK (purpose IN ('administrator', 'advisor')),
    CONSTRAINT ck_authority_keys_verification_reference CHECK (length(trim(verification_reference)) > 0)
);
CREATE INDEX ix_authority_keys_user_id ON authority_keys(user_id);

CREATE TABLE fabric_transactions (
    request_id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    sequence BIGINT,
    fabric_transaction_id VARCHAR(128) UNIQUE,
    submitted_transaction_id VARCHAR(128),
    channel_name VARCHAR(128),
    chaincode_name VARCHAR(128),
    block_number BIGINT,
    validation_code INTEGER,
    schema_version INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    object_ids JSONB NOT NULL,
    payload JSONB NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id),
    actor_key_id UUID NOT NULL,
    signature BYTEA,
    committed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fabric_transactions_source CHECK (source IN ('fabric', 'legacy')),
    CONSTRAINT ck_fabric_transactions_positive_sequence CHECK (sequence IS NULL OR sequence > 0),
    CONSTRAINT ck_fabric_transactions_positive_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_fabric_transactions_event_type_format CHECK (event_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_fabric_transactions_object_ids_object CHECK (jsonb_typeof(object_ids) = 'object'),
    CONSTRAINT ck_fabric_transactions_payload_object CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX ix_fabric_transactions_actor_id ON fabric_transactions(actor_id);
CREATE INDEX ix_fabric_transactions_event_type_sequence ON fabric_transactions(event_type, sequence);

CREATE TABLE administrator (
    id INTEGER PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    key_id UUID NOT NULL UNIQUE REFERENCES authority_keys(id),
    bootstrap_transaction_id UUID NOT NULL UNIQUE REFERENCES fabric_transactions(request_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_administrator_singleton CHECK (id = 1)
);

CREATE TABLE appointments (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id),
    user_id UUID NOT NULL REFERENCES users(id),
    position VARCHAR(32) NOT NULL,
    granted_by_id UUID NOT NULL REFERENCES users(id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    grant_transaction_id UUID NOT NULL UNIQUE REFERENCES fabric_transactions(request_id),
    revoked_at TIMESTAMPTZ,
    revocation_transaction_id UUID UNIQUE REFERENCES fabric_transactions(request_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    advisor_key_id UUID REFERENCES authority_keys(id),
    verification_reference VARCHAR(500),
    CONSTRAINT ck_appointments_position CHECK (position IN ('faculty_advisor', 'club_head', 'office_bearer')),
    CONSTRAINT ck_appointments_term_order CHECK (ends_at > starts_at),
    CONSTRAINT ck_appointments_revocation_time CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_appointments_revocation_reference CHECK (
        (revoked_at IS NULL AND revocation_transaction_id IS NULL) OR
        (revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)
    )
);
CREATE INDEX ix_appointments_club_id_user_id ON appointments(club_id, user_id);
CREATE INDEX ix_appointments_user_id ON appointments(user_id);

CREATE TABLE competitions (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id),
    title VARCHAR(250) NOT NULL,
    description TEXT,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    created_by_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_competitions_nonempty_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_competitions_date_order CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX ix_competitions_club_id ON competitions(club_id);

CREATE TABLE competition_results (
    id UUID PRIMARY KEY,
    competition_id UUID NOT NULL REFERENCES competitions(id),
    created_by_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_competition_results_competition_id ON competition_results(competition_id);

CREATE TABLE result_submissions (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL REFERENCES competition_results(id),
    version INTEGER NOT NULL,
    award VARCHAR(250) NOT NULL,
    proof_digest VARCHAR(64) NOT NULL,
    submitted_by_id UUID NOT NULL REFERENCES users(id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_result_submissions_positive_version CHECK (version > 0),
    CONSTRAINT ck_result_submissions_nonempty_award CHECK (length(trim(award)) > 0),
    CONSTRAINT ck_result_submissions_proof_digest_format CHECK (proof_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_result_submissions_result_version UNIQUE(result_id, version)
);
CREATE INDEX ix_result_submissions_result_id ON result_submissions(result_id);

CREATE TABLE result_recipients (
    submission_id UUID NOT NULL REFERENCES result_submissions(id),
    recipient_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY(submission_id, recipient_id)
);
CREATE INDEX ix_result_recipients_recipient_id ON result_recipients(recipient_id);

CREATE TABLE credentials (
    id UUID PRIMARY KEY,
    competition_id UUID NOT NULL REFERENCES competitions(id),
    recipient_id UUID NOT NULL REFERENCES users(id),
    advisor_id UUID NOT NULL REFERENCES users(id),
    award VARCHAR(250) NOT NULL,
    result_id UUID NOT NULL,
    submission_version INTEGER NOT NULL,
    proof_digest VARCHAR(64) NOT NULL,
    issuance_transaction_id UUID NOT NULL REFERENCES fabric_transactions(request_id),
    issued_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_transaction_id UUID UNIQUE REFERENCES fabric_transactions(request_id),
    supersedes_id UUID UNIQUE REFERENCES credentials(id),
    CONSTRAINT ck_credentials_nonempty_award CHECK (length(trim(award)) > 0),
    CONSTRAINT ck_credentials_positive_submission_version CHECK (submission_version > 0),
    CONSTRAINT ck_credentials_proof_digest_format CHECK (proof_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_credentials_revocation_time CHECK (revoked_at IS NULL OR revoked_at >= issued_at),
    CONSTRAINT ck_credentials_revocation_reference CHECK (
        (revoked_at IS NULL AND revocation_transaction_id IS NULL) OR
        (revoked_at IS NOT NULL AND revocation_transaction_id IS NOT NULL)
    ),
    CONSTRAINT ck_credentials_no_self_supersession CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CONSTRAINT uq_credentials_result_version_recipient UNIQUE(result_id, submission_version, recipient_id)
);
CREATE INDEX ix_credentials_advisor_id ON credentials(advisor_id);
CREATE INDEX ix_credentials_competition_id ON credentials(competition_id);
CREATE INDEX ix_credentials_recipient_id ON credentials(recipient_id);
CREATE INDEX ix_credentials_issuance_transaction_id ON credentials(issuance_transaction_id);

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$;
CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION reject_result_record_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'submitted result versions are immutable';
END;
$$;
CREATE TRIGGER competition_results_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON competition_results
    FOR EACH STATEMENT EXECUTE FUNCTION reject_result_record_mutation();
CREATE TRIGGER result_submissions_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON result_submissions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_result_record_mutation();
CREATE TRIGGER result_recipients_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON result_recipients
    FOR EACH STATEMENT EXECUTE FUNCTION reject_result_record_mutation();

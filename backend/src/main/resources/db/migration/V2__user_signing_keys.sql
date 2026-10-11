CREATE TABLE user_signing_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    public_key BYTEA NOT NULL,
    algorithm VARCHAR(16) NOT NULL DEFAULT 'Ed25519',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_user_signing_keys_public_key_length CHECK (octet_length(public_key) = 32),
    CONSTRAINT ck_user_signing_keys_algorithm CHECK (algorithm = 'Ed25519'),
    CONSTRAINT ck_user_signing_keys_revocation_time CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    )
);

CREATE UNIQUE INDEX uq_user_signing_keys_active_user
    ON user_signing_keys(user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX uq_user_signing_keys_active_public_key
    ON user_signing_keys(public_key) WHERE revoked_at IS NULL;
CREATE INDEX ix_user_signing_keys_user_id ON user_signing_keys(user_id);

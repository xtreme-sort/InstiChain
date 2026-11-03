CREATE TABLE storage_oauth_states (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_storage_oauth_state_hash CHECK (state_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_storage_oauth_states_owner_created ON storage_oauth_states(owner_id, created_at DESC);

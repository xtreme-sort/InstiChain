CREATE TABLE storage_connections (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(32) NOT NULL,
    encrypted_refresh_token BYTEA,
    granted_scopes TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disconnected_at TIMESTAMPTZ,
    CONSTRAINT uq_storage_connections_owner_provider UNIQUE (owner_id, provider),
    CONSTRAINT ck_storage_connections_status CHECK (status IN ('connected', 'disconnected', 'needs_reconnect')),
    CONSTRAINT ck_storage_connections_secret CHECK (
        (status = 'connected' AND encrypted_refresh_token IS NOT NULL AND disconnected_at IS NULL)
        OR (status <> 'connected' AND encrypted_refresh_token IS NULL)
    )
);
CREATE INDEX ix_storage_connections_owner ON storage_connections(owner_id);

CREATE TABLE proof_artifacts (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    connection_id UUID REFERENCES storage_connections(id) ON DELETE SET NULL,
    provider VARCHAR(32) NOT NULL,
    provider_file_id VARCHAR(1024) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256_digest VARCHAR(64) NOT NULL,
    archive_path TEXT NOT NULL,
    availability VARCHAR(32) NOT NULL DEFAULT 'unchecked',
    checked_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_proof_artifacts_size CHECK (byte_size > 0),
    CONSTRAINT ck_proof_artifacts_digest CHECK (sha256_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_proof_artifacts_availability CHECK (
        availability IN ('unchecked', 'matching', 'provider_missing', 'provider_changed',
                         'archive_missing', 'disconnected', 'temporarily_unavailable'))
);
CREATE INDEX ix_proof_artifacts_owner_archived ON proof_artifacts(owner_id, archived_at DESC);

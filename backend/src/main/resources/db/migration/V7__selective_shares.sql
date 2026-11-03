ALTER TABLE credentials ADD CONSTRAINT uq_credentials_id_recipient UNIQUE (id, recipient_id);

CREATE TABLE share_links (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(120),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    revoked_by_id UUID REFERENCES users(id),
    CONSTRAINT uq_share_links_id_owner UNIQUE (id, owner_id),
    CONSTRAINT ck_share_links_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_share_links_expiry CHECK (expires_at > created_at
        AND expires_at <= created_at + interval '30 days'),
    CONSTRAINT ck_share_links_revocation CHECK (
        (revoked_at IS NULL AND revoked_by_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_id IS NOT NULL))
);
CREATE INDEX ix_share_links_owner_created ON share_links(owner_id, created_at DESC);
CREATE INDEX ix_share_links_active_expiry ON share_links(expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE share_link_credentials (
    share_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    PRIMARY KEY (share_id, credential_id),
    CONSTRAINT fk_share_link_owner FOREIGN KEY (share_id, owner_id)
        REFERENCES share_links(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT fk_share_credential_owner FOREIGN KEY (credential_id, owner_id)
        REFERENCES credentials(id, recipient_id)
);
CREATE INDEX ix_share_link_credentials_credential ON share_link_credentials(credential_id);

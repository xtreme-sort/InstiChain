-- Key revocations are institutional events, so each one is bound to its Fabric receipt.
ALTER TABLE authority_keys
    ADD COLUMN revocation_transaction_id UUID UNIQUE REFERENCES fabric_transactions(request_id),
    ADD COLUMN revocation_reason VARCHAR(32),
    ADD COLUMN replaces_id UUID REFERENCES authority_keys(id);
ALTER TABLE authority_keys ADD CONSTRAINT ck_authority_keys_revocation_reference
    CHECK (revocation_transaction_id IS NULL OR revoked_at IS NOT NULL);
ALTER TABLE authority_keys ADD CONSTRAINT ck_authority_keys_revocation_reason
    CHECK (revocation_reason IS NULL OR revocation_reason IN ('rotated', 'recovered', 'compromised'));
ALTER TABLE authority_keys ADD CONSTRAINT ck_authority_keys_no_self_replacement
    CHECK (replaces_id IS NULL OR replaces_id <> id);

-- A device-loss request waits for an administrator's out-of-band identity check.
CREATE TABLE key_recovery_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    public_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution VARCHAR(16),
    resolved_by_id UUID REFERENCES users(id),
    transaction_id UUID UNIQUE REFERENCES fabric_transactions(request_id),
    CONSTRAINT ck_key_recovery_requests_public_key_length CHECK (octet_length(public_key) = 32),
    CONSTRAINT ck_key_recovery_requests_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_key_recovery_requests_resolution CHECK (
        (resolved_at IS NULL AND resolution IS NULL AND resolved_by_id IS NULL)
        OR (resolved_at IS NOT NULL AND resolution IN ('approved', 'cancelled')
            AND resolved_by_id IS NOT NULL)),
    CONSTRAINT ck_key_recovery_requests_receipt CHECK (
        (resolution = 'approved') = (transaction_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_key_recovery_requests_pending
    ON key_recovery_requests(user_id) WHERE resolved_at IS NULL;

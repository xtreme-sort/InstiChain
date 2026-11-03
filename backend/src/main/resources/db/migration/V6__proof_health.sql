ALTER TABLE proof_artifacts
    ADD COLUMN archive_status VARCHAR(24) NOT NULL DEFAULT 'unchecked',
    ADD COLUMN provider_status VARCHAR(32) NOT NULL DEFAULT 'unchecked';
ALTER TABLE proof_artifacts
    ADD CONSTRAINT ck_proof_artifacts_archive_status
    CHECK (archive_status IN ('unchecked', 'matching', 'missing', 'changed')),
    ADD CONSTRAINT ck_proof_artifacts_provider_status
    CHECK (provider_status IN ('unchecked', 'matching', 'missing', 'changed',
                              'disconnected', 'temporarily_unavailable'));
ALTER TABLE proof_artifacts DROP CONSTRAINT ck_proof_artifacts_availability;
ALTER TABLE proof_artifacts ADD CONSTRAINT ck_proof_artifacts_availability
    CHECK (availability IN ('unchecked', 'matching', 'provider_missing', 'provider_changed',
                           'archive_missing', 'archive_changed', 'disconnected',
                           'temporarily_unavailable'));

ALTER TABLE result_submissions
    ADD COLUMN proof_artifact_id UUID REFERENCES proof_artifacts(id);
CREATE INDEX ix_result_submissions_proof_artifact ON result_submissions(proof_artifact_id)
    WHERE proof_artifact_id IS NOT NULL;
CREATE UNIQUE INDEX uq_proof_artifacts_owner_file_digest
    ON proof_artifacts(owner_id, provider, provider_file_id, sha256_digest);

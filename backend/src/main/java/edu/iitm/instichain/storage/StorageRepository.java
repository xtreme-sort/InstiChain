package edu.iitm.instichain.storage;

import edu.iitm.instichain.common.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StorageRepository {
    private final JdbcTemplate jdbc;

    public StorageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Connection(UUID id, UUID ownerId, String provider, byte[] encryptedRefreshToken,
                             String scopes, String status, OffsetDateTime updatedAt) {
        @Override public String toString() {
            return "Connection[id=" + id + ", ownerId=" + ownerId + ", provider=" + provider
                    + ", status=" + status + ", encryptedRefreshToken=[REDACTED]]";
        }
    }

    public record Artifact(UUID id, UUID ownerId, UUID connectionId, String provider,
                           String providerFileId, String displayName, String mimeType,
                           long byteSize, String digest, String archivePath, String availability,
                           OffsetDateTime checkedAt) {
        @Override public String toString() {
            return "Artifact[id=" + id + ", ownerId=" + ownerId + ", digest=" + digest
                    + ", providerFileId=[REDACTED], archivePath=[REDACTED]]";
        }
    }

    public Connection connection(UUID ownerId, String provider) {
        List<Connection> rows = jdbc.query("""
                SELECT id, owner_id, provider, encrypted_refresh_token, granted_scopes, status, updated_at
                FROM storage_connections WHERE owner_id = ? AND provider = ?
                """, (row, number) -> new Connection(row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class), row.getString("provider"),
                row.getBytes("encrypted_refresh_token"), row.getString("granted_scopes"),
                row.getString("status"), row.getObject("updated_at", OffsetDateTime.class)), ownerId, provider);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Artifact ownedArtifact(UUID ownerId, UUID artifactId) {
        List<Artifact> rows = jdbc.query("""
                SELECT id, owner_id, connection_id, provider, provider_file_id, display_name,
                       mime_type, byte_size, sha256_digest, archive_path, availability, checked_at
                FROM proof_artifacts WHERE owner_id = ? AND id = ?
                """, (row, number) -> new Artifact(row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class), row.getObject("connection_id", UUID.class),
                row.getString("provider"), row.getString("provider_file_id"),
                row.getString("display_name"), row.getString("mime_type"),
                row.getLong("byte_size"), row.getString("sha256_digest"),
                row.getString("archive_path"), row.getString("availability"),
                row.getObject("checked_at", OffsetDateTime.class)), ownerId, artifactId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Proof artifact not found.");
        return rows.getFirst();
    }
}

package edu.iitm.instichain.storage;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProofHealthService {
    private final JdbcTemplate jdbc;
    private final StorageRepository storage;
    private final GoogleConnectionService connection;
    private final StorageAdapter adapter;
    private final InstiChainProperties properties;

    public ProofHealthService(JdbcTemplate jdbc, StorageRepository storage,
                              GoogleConnectionService connection, StorageAdapter adapter,
                              InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.connection = connection;
        this.adapter = adapter;
        this.properties = properties;
    }

    public record Health(String archiveStatus, String providerStatus,
                         OffsetDateTime checkedAt, String availability) {}

    public Health cached(UUID ownerId, UUID artifactId) {
        storage.ownedArtifact(ownerId, artifactId);
        return row(artifactId);
    }

    public Health check(UUID ownerId, UUID artifactId) {
        StorageRepository.Artifact artifact = storage.ownedArtifact(ownerId, artifactId);
        String archive = archiveStatus(artifact);
        String provider = providerStatus(ownerId, artifact);
        String availability = "matching".equals(archive) ? "matching"
                : "changed".equals(archive) ? "archive_changed" : "archive_missing";
        if (!"matching".equals(provider)) {
            availability = switch (provider) {
                case "missing" -> "provider_missing";
                case "changed" -> "provider_changed";
                case "disconnected" -> "disconnected";
                case "temporarily_unavailable" -> "temporarily_unavailable";
                default -> availability;
            };
        }
        jdbc.update("""
                UPDATE proof_artifacts SET archive_status = ?, provider_status = ?,
                    availability = ?, checked_at = clock_timestamp()
                WHERE id = ? AND owner_id = ?
                """, archive, provider, availability, artifactId, ownerId);
        return row(artifactId);
    }

    public Health forCredential(UUID studentId, UUID credentialId) {
        List<UUID> artifacts = jdbc.queryForList("""
                SELECT s.proof_artifact_id FROM credentials cr
                JOIN result_submissions s ON s.result_id = cr.result_id
                    AND s.version = cr.submission_version
                WHERE cr.id = ? AND cr.recipient_id = ?
                """, UUID.class, credentialId, studentId);
        if (artifacts.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Credential not found.");
        if (artifacts.getFirst() == null) return new Health("not_archived", "not_checked", null, "digest_only");
        return row(artifacts.getFirst());
    }

    private Health row(UUID id) {
        List<Health> rows = jdbc.query("""
                SELECT archive_status, provider_status, checked_at, availability
                FROM proof_artifacts WHERE id = ?
                """, (result, number) -> new Health(result.getString("archive_status"),
                result.getString("provider_status"),
                result.getObject("checked_at", OffsetDateTime.class),
                result.getString("availability")), id);
        return rows.isEmpty() ? new Health("missing", "not_checked", null, "archive_missing")
                : rows.getFirst();
    }

    private String archiveStatus(StorageRepository.Artifact artifact) {
        Path path = Path.of(artifact.archivePath());
        if (!Files.isRegularFile(path)) return "missing";
        try (InputStream input = Files.newInputStream(path)) {
            return artifact.digest().equals(digest(input, properties.maxProofBytes())) ? "matching" : "changed";
        } catch (Exception error) { return "missing"; }
    }

    private String providerStatus(UUID ownerId, StorageRepository.Artifact artifact) {
        StorageRepository.Connection connected = storage.connection(ownerId, artifact.provider());
        if (connected == null || !"connected".equals(connected.status())) return "disconnected";
        for (int attempt = 0; attempt < 2; attempt++) {
            try (InputStream input = adapter.download(connection.accessToken(ownerId),
                    artifact.providerFileId())) {
                return artifact.digest().equals(digest(input, properties.maxProofBytes()))
                        ? "matching" : "changed";
            } catch (ApiException error) {
                if (error.status() == HttpStatus.NOT_FOUND) return "missing";
                if (attempt == 1) return "temporarily_unavailable";
            } catch (Exception error) {
                if (attempt == 1) return "temporarily_unavailable";
            }
        }
        return "temporarily_unavailable";
    }

    static String digest(InputStream input, long maxBytes) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long readTotal = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            readTotal += count;
            if (readTotal > maxBytes) throw new IllegalArgumentException("Proof exceeds size limit");
            sha.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}

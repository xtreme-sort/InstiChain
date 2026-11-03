package edu.iitm.instichain.storage;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProofImportService {
    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp");
    private final JdbcTemplate jdbc;
    private final StorageRepository storage;
    private final GoogleConnectionService connection;
    private final StorageAdapter adapter;
    private final InstiChainProperties properties;

    public ProofImportService(JdbcTemplate jdbc, StorageRepository storage,
                              GoogleConnectionService connection, StorageAdapter adapter,
                              InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.connection = connection;
        this.adapter = adapter;
        this.properties = properties;
    }

    public record Proof(UUID id, String displayName, String mimeType, long byteSize,
                        String sha256Digest, String availability, OffsetDateTime archivedAt) {}

    @Transactional
    public Proof importFile(UUID ownerId, String fileId) {
        StorageRepository.Connection connected = storage.connection(ownerId, adapter.provider());
        if (connected == null || !"connected".equals(connected.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Connect Google Drive first.");
        }
        String accessToken = connection.accessToken(ownerId);
        StorageAdapter.FileMetadata metadata = adapter.metadata(accessToken, fileId);
        if (!fileId.equals(metadata.id()) || !ALLOWED_MIME.contains(metadata.mimeType())
                || metadata.size() <= 0 || metadata.size() > properties.maxProofBytes()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only PDF, PNG, JPEG, or WebP files within the proof size limit are supported.");
        }
        Path directory = properties.proofArchiveDirectory().toAbsolutePath().normalize();
        Path temporary = null;
        Path archived = null;
        try {
            Files.createDirectories(directory);
            try { Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { }
            temporary = Files.createTempFile(directory, "import-", ".tmp");
            temporary.toFile().setReadable(false, false);
            temporary.toFile().setReadable(true, true);
            temporary.toFile().setWritable(false, false);
            temporary.toFile().setWritable(true, true);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = adapter.download(accessToken, fileId);
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > properties.maxProofBytes() || size > metadata.size()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Proof exceeds the permitted size.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size != metadata.size()) {
                throw new ApiException(HttpStatus.CONFLICT, "The Drive file changed during import. Try again.");
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Proof existing = existing(ownerId, fileId, hash);
            if (existing != null) return existing;
            UUID id = UUID.randomUUID();
            archived = directory.resolve(id + ".bin");
            Files.move(temporary, archived, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            String safeName = metadata.name().replaceAll("[\\p{Cntrl}]", " ").strip();
            if (safeName.isEmpty()) safeName = "proof";
            if (safeName.length() > 255) safeName = safeName.substring(0, 255);
            List<UUID> inserted = jdbc.queryForList("""
                        INSERT INTO proof_artifacts(id, owner_id, connection_id, provider,
                            provider_file_id, display_name, mime_type, byte_size,
                            sha256_digest, archive_path)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (owner_id, provider, provider_file_id, sha256_digest)
                            DO NOTHING RETURNING id
                        """, UUID.class, id, ownerId, connected.id(), adapter.provider(), fileId,
                    safeName, metadata.mimeType(), size, hash, archived.toString());
            if (inserted.isEmpty()) {
                Files.deleteIfExists(archived);
                archived = null;
                Proof winner = existing(ownerId, fileId, hash);
                if (winner != null) return winner;
                throw new ApiException(HttpStatus.CONFLICT, "Proof import was not saved. Try again.");
            }
            Path cleanup = archived;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int outcome) {
                        if (outcome != STATUS_COMMITTED) {
                            try { Files.deleteIfExists(cleanup); } catch (Exception ignored) { }
                        }
                    }
                });
            }
            return new Proof(id, safeName, metadata.mimeType(), size, hash,
                    "unchecked", OffsetDateTime.now());
        } catch (ApiException error) {
            try { if (archived != null) Files.deleteIfExists(archived); } catch (Exception ignored) { }
            throw error;
        } catch (Exception error) {
            try { if (archived != null) Files.deleteIfExists(archived); } catch (Exception ignored) { }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Proof import failed; no file was saved.");
        } finally {
            try { if (temporary != null) Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    public List<Proof> list(UUID ownerId) {
        return jdbc.query("""
                SELECT id, display_name, mime_type, byte_size, sha256_digest, availability, archived_at
                FROM proof_artifacts WHERE owner_id = ? ORDER BY archived_at DESC, id
                """, (row, number) -> new Proof(row.getObject("id", UUID.class),
                row.getString("display_name"), row.getString("mime_type"), row.getLong("byte_size"),
                row.getString("sha256_digest"), row.getString("availability"),
                row.getObject("archived_at", OffsetDateTime.class)), ownerId);
    }

    private Proof existing(UUID ownerId, String fileId, String digest) {
        List<Proof> rows = jdbc.query("""
                SELECT id, display_name, mime_type, byte_size, sha256_digest, availability, archived_at
                FROM proof_artifacts WHERE owner_id = ? AND provider = ? AND provider_file_id = ?
                  AND sha256_digest = ?
                """, (row, number) -> new Proof(row.getObject("id", UUID.class),
                row.getString("display_name"), row.getString("mime_type"), row.getLong("byte_size"),
                row.getString("sha256_digest"), row.getString("availability"),
                row.getObject("archived_at", OffsetDateTime.class)), ownerId, adapter.provider(), fileId, digest);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}

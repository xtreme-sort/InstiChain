package edu.iitm.instichain.keys;

import edu.iitm.instichain.common.UuidV5;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KeyRepository {
    private final JdbcTemplate jdbc;

    public KeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record AuthorityKey(UUID id, UUID userId, byte[] publicKey, OffsetDateTime createdAt,
                               OffsetDateTime revokedAt) {}
    public record RecoveryRequest(UUID id, UUID userId, String email, String displayName,
                                  byte[] publicKey, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}
    public record AffectedCredential(UUID id, String award, String competition, OffsetDateTime issuedAt) {}

    /** The derivation AdminController uses, so a key has one ID however it was registered. */
    public static UUID advisorKeyId(byte[] publicKey) {
        return UuidV5.url("instichain:advisor-key:" + Base64.getEncoder().encodeToString(publicKey));
    }

    public static UUID userSigningKeyId(byte[] publicKey) {
        return UuidV5.url("instichain:user-signing-key:" + Base64.getEncoder().encodeToString(publicKey));
    }

    private static AuthorityKey map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new AuthorityKey(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getBytes("public_key"), row.getObject("created_at", OffsetDateTime.class),
                row.getObject("revoked_at", OffsetDateTime.class));
    }

    public Optional<AuthorityKey> currentAdvisorKey(UUID userId) {
        return jdbc.query("""
                SELECT id, user_id, public_key, created_at, revoked_at FROM authority_keys
                WHERE user_id = ? AND purpose = 'advisor' AND revoked_at IS NULL
                ORDER BY created_at DESC, id LIMIT 1
                """, KeyRepository::map, userId).stream().findFirst();
    }

    public Optional<AuthorityKey> advisorKey(UUID keyId) {
        return jdbc.query("""
                SELECT id, user_id, public_key, created_at, revoked_at FROM authority_keys
                WHERE id = ? AND purpose = 'advisor'
                """, KeyRepository::map, keyId).stream().findFirst();
    }

    public List<UUID> activeAdvisorKeyIds(UUID userId) {
        return jdbc.queryForList("""
                SELECT id FROM authority_keys
                WHERE user_id = ? AND purpose = 'advisor' AND revoked_at IS NULL ORDER BY id
                """, UUID.class, userId);
    }

    /** The newest advisor key in any state; the replacement chain continues from it. */
    public Optional<UUID> latestAdvisorKeyId(UUID userId) {
        return jdbc.queryForList("""
                SELECT id FROM authority_keys WHERE user_id = ? AND purpose = 'advisor'
                ORDER BY created_at DESC, id LIMIT 1
                """, UUID.class, userId).stream().findFirst();
    }

    /** True for any key the system has ever seen, so a retired key can never return. */
    public boolean publicKeyKnown(byte[] publicKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM authority_keys WHERE public_key = ?)
                    OR EXISTS(SELECT 1 FROM user_signing_keys WHERE public_key = ?)
                    OR EXISTS(SELECT 1 FROM key_recovery_requests
                              WHERE public_key = ? AND resolved_at IS NULL)
                """, Boolean.class, publicKey, publicKey, publicKey));
    }

    public void insertAdvisorKey(UUID id, UUID userId, byte[] publicKey, UUID verifiedById,
                                 String reference, OffsetDateTime createdAt, UUID replacesId) {
        jdbc.update("""
                INSERT INTO authority_keys(id, user_id, public_key, purpose, verified_by_id,
                    verification_reference, created_at, replaces_id)
                VALUES (?, ?, ?, 'advisor', ?, ?, ?, ?)
                """, id, userId, publicKey, verifiedById, reference, createdAt, replacesId);
    }

    public void retireAuthorityKey(UUID keyId, OffsetDateTime at, UUID transactionId, String reason) {
        jdbc.update("""
                UPDATE authority_keys SET revoked_at = ?, revocation_transaction_id = ?,
                    revocation_reason = ? WHERE id = ? AND revoked_at IS NULL
                """, at, transactionId, reason, keyId);
    }

    /** Swaps the account key the browser registered; at most one is ever active. */
    public void replaceUserSigningKey(UUID userId, byte[] publicKey) {
        jdbc.update("""
                UPDATE user_signing_keys SET revoked_at = clock_timestamp()
                WHERE user_id = ? AND revoked_at IS NULL
                """, userId);
        jdbc.update("INSERT INTO user_signing_keys(id, user_id, public_key) VALUES (?, ?, ?)",
                userSigningKeyId(publicKey), userId, publicKey);
    }

    public void retireUserSigningKey(byte[] publicKey) {
        jdbc.update("""
                UPDATE user_signing_keys SET revoked_at = clock_timestamp()
                WHERE public_key = ? AND revoked_at IS NULL
                """, (Object) publicKey);
    }

    public void cancelPendingRecovery(UUID userId, UUID cancelledBy) {
        jdbc.update("""
                UPDATE key_recovery_requests SET resolved_at = clock_timestamp(),
                    resolution = 'cancelled', resolved_by_id = ?
                WHERE user_id = ? AND resolved_at IS NULL
                """, cancelledBy, userId);
    }

    public int recoveryRequestsToday(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM key_recovery_requests
                WHERE user_id = ? AND created_at > clock_timestamp() - interval '1 day'
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public void insertRecovery(UUID id, UUID userId, byte[] publicKey, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO key_recovery_requests(id, user_id, public_key, expires_at)
                VALUES (?, ?, ?, ?)
                """, id, userId, publicKey, expiresAt);
    }

    private static RecoveryRequest mapRecovery(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new RecoveryRequest(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getString("email"), row.getString("display_name"), row.getBytes("public_key"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("expires_at", OffsetDateTime.class));
    }

    private static final String RECOVERY_SELECT = """
            SELECT r.id, r.user_id, u.email, u.display_name, r.public_key, r.created_at, r.expires_at
            FROM key_recovery_requests r JOIN users u ON u.id = r.user_id
            WHERE r.resolved_at IS NULL AND r.expires_at > clock_timestamp()
            """;

    public List<RecoveryRequest> pendingRecoveries() {
        return jdbc.query(RECOVERY_SELECT + " ORDER BY r.created_at", KeyRepository::mapRecovery);
    }

    public Optional<RecoveryRequest> pendingRecovery(UUID requestId) {
        return jdbc.query(RECOVERY_SELECT + " AND r.id = ? FOR UPDATE OF r",
                KeyRepository::mapRecovery, requestId).stream().findFirst();
    }

    public Optional<RecoveryRequest> pendingRecoveryFor(UUID userId) {
        return jdbc.query(RECOVERY_SELECT + " AND r.user_id = ?",
                KeyRepository::mapRecovery, userId).stream().findFirst();
    }

    public boolean approvedRecovery(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM key_recovery_requests WHERE id = ? AND resolution = 'approved')
                """, Boolean.class, requestId));
    }

    public void resolveRecovery(UUID requestId, String resolution, UUID resolvedBy, UUID transactionId) {
        jdbc.update("""
                UPDATE key_recovery_requests SET resolved_at = clock_timestamp(), resolution = ?,
                    resolved_by_id = ?, transaction_id = ? WHERE id = ? AND resolved_at IS NULL
                """, resolution, resolvedBy, transactionId, requestId);
    }

    /** Credentials the key signed from the moment it was compromised, for explicit review. */
    public List<AffectedCredential> credentialsSignedSince(UUID keyId, OffsetDateTime since) {
        return jdbc.query("""
                SELECT cr.id, cr.award, c.title, cr.issued_at
                FROM credentials cr
                JOIN fabric_transactions ft ON ft.request_id = cr.issuance_transaction_id
                JOIN competitions c ON c.id = cr.competition_id
                WHERE ft.actor_key_id = ? AND ft.committed_at >= ? AND cr.revoked_at IS NULL
                ORDER BY cr.issued_at, cr.id
                """, (row, number) -> new AffectedCredential(row.getObject("id", UUID.class),
                row.getString("award"), row.getString("title"),
                row.getObject("issued_at", OffsetDateTime.class)), keyId, since);
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }
}

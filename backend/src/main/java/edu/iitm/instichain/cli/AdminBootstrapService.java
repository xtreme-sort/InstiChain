package edu.iitm.instichain.cli;

import edu.iitm.instichain.auth.InstituteEmail;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.common.UuidV5;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.domain.AdminController;
import edu.iitm.instichain.fabric.LedgerEventService;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBootstrapService {
    private final JdbcTemplate jdbc;
    private final LedgerEventService ledger;
    private final InstiChainProperties properties;

    public AdminBootstrapService(JdbcTemplate jdbc, LedgerEventService ledger,
                                 InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Transactional
    public UUID bootstrap(String submittedEmail, String submittedReference) throws Exception {
        String email = InstituteEmail.normalize(submittedEmail);
        String reference = submittedReference == null ? "" : submittedReference.strip();
        if (reference.isEmpty() || reference.length() > 500) {
            throw new IllegalArgumentException(
                    "Provide an institutional verification reference (1-500 characters).");
        }
        ledger.lock();
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, email_verified_at FROM users WHERE email = ?", email);
        if (users.isEmpty() || users.getFirst().get("email_verified_at") == null) {
            throw new IllegalArgumentException(
                    "The administrator must have an existing verified institute account.");
        }
        UUID userId = (UUID) users.getFirst().get("id");
        PrivateKey privateKey = Ed25519.readPrivateKey(properties.adminSigningKeyFile());
        byte[] publicKey = Ed25519.rawPublic(privateKey);
        UUID keyId = UuidV5.url("instichain:admin-key:" + userId);

        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT a.user_id, a.revoked_at, k.id AS key_id, k.public_key, k.revoked_at AS key_revoked_at
                FROM administrator a JOIN authority_keys k ON k.id = a.key_id WHERE a.id = 1
                """);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.getFirst();
            if (userId.equals(row.get("user_id")) && keyId.equals(row.get("key_id"))
                    && row.get("revoked_at") == null && row.get("key_revoked_at") == null
                    && java.util.Arrays.equals(publicKey, (byte[]) row.get("public_key"))) {
                return userId;
            }
            throw new IllegalArgumentException(
                    "Bootstrap is already complete. It cannot replace or add administrators.");
        }

        jdbc.update("""
                INSERT INTO authority_keys(id, user_id, public_key, purpose, verified_by_id, verification_reference)
                VALUES (?, ?, ?, 'administrator', ?, ?)
                """, keyId, userId, publicKey, userId, reference);
        UUID requestId = UuidV5.url("instichain:admin-bootstrap:" + userId);
        Map<String, Object> document = AdminController.action(requestId, "ADMIN_BOOTSTRAPPED",
                Map.of("user_id", userId),
                Map.of("public_key", Base64.getEncoder().encodeToString(publicKey)), userId, keyId);
        ledger.appendAdmin(document, keyId, publicKey);
        jdbc.update("""
                INSERT INTO administrator(id, user_id, key_id, bootstrap_transaction_id)
                VALUES (1, ?, ?, ?)
                """, userId, keyId, requestId);
        return userId;
    }
}

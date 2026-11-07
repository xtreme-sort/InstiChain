package edu.iitm.instichain.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.fabric.FabricService;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CredentialVerificationService {
    private final JdbcTemplate jdbc;
    private final CanonicalJson json;
    private final FabricService fabric;
    private final CredentialStatus statuses;

    public CredentialVerificationService(JdbcTemplate jdbc, CanonicalJson json,
                                         FabricService fabric, CredentialStatus statuses) {
        this.jdbc = jdbc;
        this.json = json;
        this.fabric = fabric;
        this.statuses = statuses;
    }

    public record Subject(UUID id, UUID recipientId, UUID clubId, UUID advisorId,
                   UUID issuanceId, OffsetDateTime issuedAt, OffsetDateTime revokedAt,
                   UUID revocationId, boolean superseded) {}
    record Receipt(UUID requestId, String source, Long sequence, String fabricId,
                   Integer validationCode, String eventType, String objects, String payload,
                   UUID actorId, UUID keyId, byte[] signature, OffsetDateTime committedAt) {}
    public record Verification(UUID credentialId, String authenticity, String ledgerStatus,
                               String historicalAuthorization, String lifecycleStatus,
                               String proofAvailability, String reason) {}
    public record HistoryItem(String eventType, UUID requestId, String fabricTransactionId,
                              OffsetDateTime committedAt, String reason) {}

    public Subject subject(UUID id) {
        List<Subject> rows = jdbc.query("""
                SELECT cr.id, cr.recipient_id, c.club_id, cr.advisor_id,
                       cr.issuance_transaction_id, cr.issued_at, cr.revoked_at,
                       cr.revocation_transaction_id,
                       EXISTS(SELECT 1 FROM credentials next WHERE next.supersedes_id = cr.id)
                         OR EXISTS(SELECT 1 FROM fabric_transactions successor
                           WHERE successor.event_type = 'ACHIEVEMENT_ISSUED'
                             AND successor.payload->'superseded_credential_ids'
                               @> jsonb_build_array(cr.id::text)) AS superseded
                FROM credentials cr JOIN competitions c ON c.id = cr.competition_id
                WHERE cr.id = ?
                """, (row, number) -> new Subject(row.getObject("id", UUID.class),
                row.getObject("recipient_id", UUID.class), row.getObject("club_id", UUID.class),
                row.getObject("advisor_id", UUID.class),
                row.getObject("issuance_transaction_id", UUID.class),
                row.getObject("issued_at", OffsetDateTime.class),
                row.getObject("revoked_at", OffsetDateTime.class),
                row.getObject("revocation_transaction_id", UUID.class), row.getBoolean("superseded")), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Credential not found.");
        return rows.getFirst();
    }

    public Verification verify(Subject subject) {
        String lifecycle = statuses.current(subject.revokedAt(), subject.superseded());
        String proof = proofAvailability(subject.id());
        Receipt issuance = receipt(subject.issuanceId());
        if (issuance == null || !"fabric".equals(issuance.source())) {
            return new Verification(subject.id(), "unknown", "missing_receipt", "unknown",
                    lifecycle, proof, "Issuance receipt is missing or legacy.");
        }
        byte[] publicKey = advisorPublicKey(issuance.keyId());
        String signature = signatureStatus(issuance, publicKey);
        String ledger = ledgerStatus(issuance);
        boolean linked = issuanceLinksSubject(subject, issuance);
        String authenticity = !linked || "invalid".equals(signature) || "mismatch".equals(ledger) ? "invalid"
                : "valid".equals(signature) && "confirmed".equals(ledger) ? "valid" : "unknown";
        String authorization = linked ? authorityStatus(subject, issuance, publicKey != null) : "invalid";
        if (subject.revocationId() != null) {
            Receipt revocation = receipt(subject.revocationId());
            if (revocation == null || !"ACHIEVEMENT_REVOKED".equals(revocation.eventType())
                    || !revocationLinksSubject(subject, revocation)
                    || !"valid".equals(signatureStatus(revocation, advisorPublicKey(revocation.keyId())))
                    || !"confirmed".equals(ledgerStatus(revocation))) {
                ledger = "lifecycle_receipt_unverified";
                if (!"invalid".equals(authenticity)) authenticity = "unknown";
            }
        }
        return new Verification(subject.id(), authenticity, ledger, authorization,
                lifecycle, proof, null);
    }

    public List<HistoryItem> history(Subject subject) {
        return jdbc.query("""
                SELECT event_type, request_id, fabric_transaction_id, committed_at,
                       CASE WHEN event_type = 'ACHIEVEMENT_REVOKED'
                            THEN payload->>'reason' ELSE NULL END AS reason
                FROM fabric_transactions
                WHERE request_id = ? OR request_id = ?
                   OR (event_type = 'ACHIEVEMENT_ISSUED'
                       AND payload->'superseded_credential_ids' @> jsonb_build_array(?::text))
                ORDER BY sequence NULLS LAST, committed_at, request_id
                """, (row, number) -> new HistoryItem(row.getString("event_type"),
                row.getObject("request_id", UUID.class), row.getString("fabric_transaction_id"),
                row.getObject("committed_at", OffsetDateTime.class), row.getString("reason")),
                subject.issuanceId(), subject.revocationId(), subject.id());
    }

    private Receipt receipt(UUID id) {
        if (id == null) return null;
        List<Receipt> rows = jdbc.query("""
                SELECT request_id, source, sequence, fabric_transaction_id, validation_code,
                       event_type, object_ids::text AS objects, payload::text AS payload,
                       actor_id, actor_key_id, signature, committed_at
                FROM fabric_transactions WHERE request_id = ?
                """, CredentialVerificationService::mapReceipt, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String proofAvailability(UUID credentialId) {
        List<String> statuses = jdbc.queryForList("""
                SELECT COALESCE(p.availability, 'digest_only')
                FROM credentials cr
                JOIN result_submissions s ON s.result_id = cr.result_id
                    AND s.version = cr.submission_version
                LEFT JOIN proof_artifacts p ON p.id = s.proof_artifact_id
                WHERE cr.id = ?
                """, String.class, credentialId);
        return statuses.isEmpty() || statuses.getFirst() == null ? "digest_only" : statuses.getFirst();
    }

    private static Receipt mapReceipt(ResultSet row, int number) throws SQLException {
        return new Receipt(row.getObject("request_id", UUID.class), row.getString("source"),
                row.getObject("sequence", Long.class), row.getString("fabric_transaction_id"),
                row.getObject("validation_code", Integer.class), row.getString("event_type"),
                row.getString("objects"), row.getString("payload"),
                row.getObject("actor_id", UUID.class), row.getObject("actor_key_id", UUID.class),
                row.getBytes("signature"), row.getObject("committed_at", OffsetDateTime.class));
    }

    private byte[] advisorPublicKey(UUID keyId) {
        List<byte[]> keys = jdbc.query("SELECT public_key FROM authority_keys WHERE id = ? AND purpose = 'advisor'",
                (row, number) -> row.getBytes("public_key"), keyId);
        return keys.isEmpty() ? null : keys.getFirst();
    }

    private String signatureStatus(Receipt receipt, byte[] publicKey) {
        if (publicKey == null || receipt.signature() == null || receipt.objects() == null
                || receipt.payload() == null) return "unknown";
        try {
            return Ed25519.verify(publicKey, json.bytes(unsigned(receipt)), receipt.signature())
                    ? "valid" : "invalid";
        } catch (RuntimeException error) {
            return "invalid";
        }
    }

    private ObjectNode unsigned(Receipt receipt) {
        ObjectNode document = (ObjectNode) json.tree(java.util.Map.of());
        document.put("request_id", receipt.requestId().toString());
        document.put("event_type", receipt.eventType());
        document.set("object_ids", json.parse(receipt.objects().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        document.set("payload", json.parse(receipt.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        document.put("actor_id", receipt.actorId().toString());
        document.put("actor_key_id", receipt.keyId().toString());
        return document;
    }

    private String ledgerStatus(Receipt receipt) {
        if (receipt == null || !"fabric".equals(receipt.source()) || receipt.fabricId() == null
                || receipt.sequence() == null || receipt.validationCode() == null
                || receipt.validationCode() != 0) return "missing_receipt";
        try {
            JsonNode event = fabric.evaluate("GetEvent", receipt.requestId().toString());
            return event.path("request_id").asText().equals(receipt.requestId().toString())
                    && event.path("event_type").asText().equals(receipt.eventType())
                    && event.path("fabric_transaction_id").asText().equals(receipt.fabricId())
                    && event.path("sequence").asLong() == receipt.sequence()
                    && event.path("actor_id").asText().equals(receipt.actorId().toString())
                    && event.path("actor_key_id").asText().equals(receipt.keyId().toString())
                    && event.path("signature").asText().equals(Base64.getEncoder().encodeToString(receipt.signature()))
                    && event.path("object_ids").equals(unsigned(receipt).path("object_ids"))
                    && event.path("payload").equals(unsigned(receipt).path("payload"))
                    && OffsetDateTime.parse(event.path("committed_at").asText())
                            .isEqual(receipt.committedAt())
                    ? "confirmed" : "mismatch";
        } catch (FabricUnavailableException error) {
            return "unavailable";
        } catch (RuntimeException error) {
            return "mismatch";
        }
    }

    private boolean issuanceLinksSubject(Subject subject, Receipt issuance) {
        if (!"ACHIEVEMENT_ISSUED".equals(issuance.eventType())) return false;
        try {
            JsonNode document = unsigned(issuance);
            boolean listed = false;
            for (JsonNode id : document.path("object_ids").path("credential_ids")) {
                if (subject.id().toString().equals(id.asText())) listed = true;
            }
            if (!listed) return false;
            for (JsonNode claim : document.path("payload").path("claims")) {
                if (subject.id().toString().equals(claim.path("credential_id").asText())
                        && subject.recipientId().toString().equals(claim.path("recipient_id").asText())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean revocationLinksSubject(Subject subject, Receipt revocation) {
        try {
            return subject.id().toString().equals(unsigned(revocation)
                    .path("object_ids").path("credential_id").asText());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String authorityStatus(Subject subject, Receipt issuance, boolean keyPresent) {
        if (!keyPresent || !"ACHIEVEMENT_ISSUED".equals(issuance.eventType())) return "unknown";
        if (!subject.advisorId().equals(issuance.actorId())) return "invalid";
        // Any of the appointed advisor's verified keys counts if it was valid at signing time,
        // so rotating a key never invalidates credentials signed before the rotation.
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM appointments a
                JOIN authority_keys k ON k.user_id = a.user_id AND k.purpose = 'advisor'
                WHERE a.club_id = ? AND a.user_id = ? AND a.position = 'faculty_advisor'
                  AND k.id = ? AND a.starts_at <= ? AND a.ends_at > ?
                  AND (a.revoked_at IS NULL OR a.revoked_at > ?)
                  AND k.created_at <= ? AND (k.revoked_at IS NULL OR k.revoked_at > ?)
                """, Integer.class, subject.clubId(), subject.advisorId(), issuance.keyId(),
                issuance.committedAt(), issuance.committedAt(), issuance.committedAt(),
                issuance.committedAt(), issuance.committedAt());
        return count != null && count > 0 ? "valid" : "invalid";
    }
}

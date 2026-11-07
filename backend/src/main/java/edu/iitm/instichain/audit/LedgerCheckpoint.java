package edu.iitm.instichain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed hash chain over the ordered Fabric event stream. Kept by a separate custodian,
 * it lets an auditor detect modified, reordered, deleted or truncated events later.
 * The format is plain JSON so a checkpoint can be verified away from this server.
 */
public final class LedgerCheckpoint {
    static final String GENESIS = "0".repeat(64);

    private LedgerCheckpoint() {}

    public record Checkpoint(int schemaVersion, String channel, String chaincode, long sequence,
                             String headRequestId, String chainHash, String createdAt,
                             String auditPublicKey, String signature) {}

    public enum Outcome { VALID, SIGNATURE_INVALID, UNTRUSTED_KEY, TRUNCATED, HISTORY_CHANGED }
    public record Verification(Outcome outcome, long checkpointSequence, long ledgerSequence,
                               Long firstDivergentSequence) {}

    /** Chains events 1..N; a gap or out-of-order sequence is itself a finding. */
    static String chain(List<JsonNode> events, CanonicalJson json, long through) {
        String hash = GENESIS;
        for (int index = 0; index < through; index++) {
            JsonNode event = events.get(index);
            if (event.path("sequence").asLong() != index + 1) {
                throw new IllegalStateException("Ledger sequence " + (index + 1) + " is missing or out of order.");
            }
            hash = sha256(hash + ":" + json.string(event));
        }
        return hash;
    }

    public static Checkpoint create(List<JsonNode> events, String channel, String chaincode, String createdAt,
                                    PrivateKey auditKey, CanonicalJson json) throws GeneralSecurityException {
        long sequence = events.size();
        String head = events.isEmpty() ? "" : events.getLast().path("request_id").asText();
        String publicKey = Base64.getEncoder().encodeToString(Ed25519.rawPublic(auditKey));
        Checkpoint unsigned = new Checkpoint(1, channel, chaincode, sequence, head,
                chain(events, json, sequence), createdAt, publicKey, null);
        String signature = Base64.getEncoder().encodeToString(Ed25519.sign(auditKey, signedBytes(unsigned, json)));
        return new Checkpoint(1, channel, chaincode, sequence, head, unsigned.chainHash(), createdAt,
                publicKey, signature);
    }

    /**
     * The trusted key must come from the auditor, never from the checkpoint file itself,
     * or a forger could simply sign a fake checkpoint with their own key.
     */
    public static Verification verify(Checkpoint checkpoint, List<JsonNode> events, String trustedPublicKey,
                                      CanonicalJson json) {
        long ledger = events.size();
        if (!checkpoint.auditPublicKey().equals(trustedPublicKey)) {
            return new Verification(Outcome.UNTRUSTED_KEY, checkpoint.sequence(), ledger, null);
        }
        byte[] signature;
        try { signature = Base64.getDecoder().decode(checkpoint.signature()); }
        catch (IllegalArgumentException | NullPointerException error) { signature = new byte[0]; }
        if (!Ed25519.verify(Base64.getDecoder().decode(trustedPublicKey), signedBytes(checkpoint, json), signature)) {
            return new Verification(Outcome.SIGNATURE_INVALID, checkpoint.sequence(), ledger, null);
        }
        if (ledger < checkpoint.sequence()) {
            return new Verification(Outcome.TRUNCATED, checkpoint.sequence(), ledger, null);
        }
        String hash = GENESIS;
        for (int index = 0; index < checkpoint.sequence(); index++) {
            JsonNode event = events.get(index);
            if (event.path("sequence").asLong() != index + 1) {
                return new Verification(Outcome.HISTORY_CHANGED, checkpoint.sequence(), ledger, (long) index + 1);
            }
            hash = sha256(hash + ":" + json.string(event));
        }
        if (!hash.equals(checkpoint.chainHash())
                || (checkpoint.sequence() > 0 && !events.get((int) checkpoint.sequence() - 1)
                        .path("request_id").asText().equals(checkpoint.headRequestId()))) {
            return new Verification(Outcome.HISTORY_CHANGED, checkpoint.sequence(), ledger, null);
        }
        return new Verification(Outcome.VALID, checkpoint.sequence(), ledger, null);
    }

    static byte[] signedBytes(Checkpoint checkpoint, CanonicalJson json) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema_version", checkpoint.schemaVersion());
        fields.put("channel", checkpoint.channel());
        fields.put("chaincode", checkpoint.chaincode());
        fields.put("sequence", checkpoint.sequence());
        fields.put("head_request_id", checkpoint.headRequestId());
        fields.put("chain_hash", checkpoint.chainHash());
        fields.put("created_at", checkpoint.createdAt());
        fields.put("audit_public_key", checkpoint.auditPublicKey());
        return json.bytes(fields);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

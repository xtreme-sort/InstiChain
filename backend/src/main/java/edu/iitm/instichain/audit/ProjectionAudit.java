package edu.iitm.instichain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares the authoritative Fabric events with PostgreSQL receipts and projections.
 * Findings name only a category, an event and a field, never payload values, so a report
 * can be shared without leaking private data.
 */
public final class ProjectionAudit {
    private ProjectionAudit() {}

    public record Receipt(UUID requestId, long sequence, String fabricTransactionId, String eventType,
                          JsonNode objectIds, JsonNode payload, UUID actorId, UUID actorKeyId,
                          byte[] signature, OffsetDateTime committedAt) {}

    public enum Kind {
        MISSING_RECEIPT, CHANGED_RECEIPT, RECEIPT_WITHOUT_EVENT, INVALID_SIGNATURE,
        UNKNOWN_SIGNING_KEY, MISSING_PROJECTION
    }

    public record Finding(Kind kind, UUID requestId, String eventType, String detail) {}

    /** Read-only view of the projection tables; the JDBC implementation is in AuditService. */
    public interface Projection {
        byte[] publicKey(UUID keyId);
        boolean clubExists(UUID clubId);
        boolean adminBootstrapped(UUID transactionId);
        boolean appointmentGranted(UUID appointmentId, UUID transactionId);
        boolean appointmentRevoked(UUID appointmentId, UUID transactionId);
        boolean credentialIssued(UUID credentialId, UUID transactionId);
        boolean credentialRevoked(UUID credentialId, UUID transactionId);
        boolean keyRetired(UUID keyId, UUID transactionId);
    }

    public static List<Finding> run(List<JsonNode> events, Map<UUID, Receipt> receipts,
                                    Projection projection, CanonicalJson json) {
        List<Finding> findings = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode event : events) {
            UUID id = UUID.fromString(event.path("request_id").asText());
            String type = event.path("event_type").asText();
            seen.add(id);
            signature(event, id, type, projection, json, findings);
            Receipt receipt = receipts.get(id);
            if (receipt == null) {
                findings.add(new Finding(Kind.MISSING_RECEIPT, id, type, "no PostgreSQL receipt"));
            } else {
                receiptMatches(event, receipt, json).forEach(field ->
                        findings.add(new Finding(Kind.CHANGED_RECEIPT, id, type, field)));
            }
            projectionMatches(event, id, type, projection).forEach(field ->
                    findings.add(new Finding(Kind.MISSING_PROJECTION, id, type, field)));
        }
        for (UUID id : receipts.keySet()) {
            if (!seen.contains(id)) {
                findings.add(new Finding(Kind.RECEIPT_WITHOUT_EVENT, id, receipts.get(id).eventType(),
                        "receipt has no Fabric event"));
            }
        }
        return findings;
    }

    private static void signature(JsonNode event, UUID id, String type, Projection projection,
                                  CanonicalJson json, List<Finding> findings) {
        byte[] key = projection.publicKey(UUID.fromString(event.path("actor_key_id").asText()));
        if (key == null) {
            findings.add(new Finding(Kind.UNKNOWN_SIGNING_KEY, id, type, "actor_key_id"));
            return;
        }
        byte[] signature;
        try { signature = Base64.getDecoder().decode(event.path("signature").asText()); }
        catch (IllegalArgumentException error) { signature = new byte[0]; }
        if (!Ed25519.verify(key, json.bytes(unsigned(event, json)), signature)) {
            findings.add(new Finding(Kind.INVALID_SIGNATURE, id, type, "signature"));
        }
    }

    /** The exact document an authority signed: the event minus everything Fabric added. */
    static ObjectNode unsigned(JsonNode event, CanonicalJson json) {
        ObjectNode document = (ObjectNode) json.tree(Map.of());
        for (String field : new String[]{"request_id", "event_type", "object_ids", "payload",
                "actor_id", "actor_key_id"}) {
            document.set(field, event.path(field));
        }
        return document;
    }

    private static List<String> receiptMatches(JsonNode event, Receipt receipt, CanonicalJson json) {
        List<String> changed = new ArrayList<>();
        if (event.path("sequence").asLong() != receipt.sequence()) changed.add("sequence");
        if (!event.path("fabric_transaction_id").asText().equals(receipt.fabricTransactionId()))
            changed.add("fabric_transaction_id");
        if (!event.path("event_type").asText().equals(receipt.eventType())) changed.add("event_type");
        if (!json.tree(event.path("object_ids")).equals(receipt.objectIds())) changed.add("object_ids");
        if (!json.tree(event.path("payload")).equals(receipt.payload())) changed.add("payload");
        if (!event.path("actor_id").asText().equals(String.valueOf(receipt.actorId()))) changed.add("actor_id");
        if (!event.path("actor_key_id").asText().equals(String.valueOf(receipt.actorKeyId())))
            changed.add("actor_key_id");
        String signature = receipt.signature() == null ? "" : Base64.getEncoder().encodeToString(receipt.signature());
        if (!event.path("signature").asText().equals(signature)) changed.add("signature");
        try {
            if (!OffsetDateTime.parse(event.path("committed_at").asText()).isEqual(receipt.committedAt()))
                changed.add("committed_at");
        } catch (RuntimeException error) {
            changed.add("committed_at");
        }
        return changed;
    }

    private static List<String> projectionMatches(JsonNode event, UUID id, String type, Projection projection) {
        JsonNode objects = event.path("object_ids");
        List<String> missing = new ArrayList<>();
        switch (type) {
            case "ADMIN_BOOTSTRAPPED" -> require(projection.adminBootstrapped(id), "administrator", missing);
            case "CLUB_CREATED" -> require(projection.clubExists(uuid(objects, "club_id")), "clubs", missing);
            case "ADVISOR_APPOINTED", "ROLE_GRANTED" -> require(
                    projection.appointmentGranted(uuid(objects, "appointment_id"), id), "appointments", missing);
            case "ROLE_REVOKED" -> require(
                    projection.appointmentRevoked(uuid(objects, "appointment_id"), id), "appointments.revoked_at", missing);
            case "ACHIEVEMENT_ISSUED" -> {
                for (UUID credential : uuids(objects.path("credential_ids"))) {
                    require(projection.credentialIssued(credential, id), "credentials", missing);
                }
            }
            case "ACHIEVEMENT_REVOKED" -> require(
                    projection.credentialRevoked(uuid(objects, "credential_id"), id), "credentials.revoked_at", missing);
            case "KEY_ROTATED" -> require(projection.keyRetired(uuid(objects, "old_key_id"), id),
                    "authority_keys.revoked_at", missing);
            case "KEY_REVOKED" -> require(projection.keyRetired(uuid(objects, "key_id"), id),
                    "authority_keys.revoked_at", missing);
            case "KEY_RECOVERED" -> {
                for (UUID key : uuids(objects.path("revoked_key_ids"))) {
                    require(projection.keyRetired(key, id), "authority_keys.revoked_at", missing);
                }
            }
            default -> { }
        }
        return missing;
    }

    private static void require(boolean present, String table, Collection<String> missing) {
        if (!present) missing.add(table);
    }

    private static UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(node.path(field).asText()); }
        catch (IllegalArgumentException error) { return new UUID(0, 0); }
    }

    private static List<UUID> uuids(JsonNode array) {
        List<UUID> values = new ArrayList<>();
        array.forEach(value -> {
            try { values.add(UUID.fromString(value.asText())); }
            catch (IllegalArgumentException error) { values.add(new UUID(0, 0)); }
        });
        return values;
    }
}

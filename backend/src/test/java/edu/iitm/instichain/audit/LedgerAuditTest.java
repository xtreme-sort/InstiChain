package edu.iitm.instichain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerAuditTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalJson json = new CanonicalJson(mapper);
    private PrivateKey signer;
    private PrivateKey auditor;
    private UUID keyId;
    private List<JsonNode> events;
    private final Set<UUID> clubs = new HashSet<>();

    private static PrivateKey generate() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
    }

    private JsonNode signedEvent(long sequence, UUID clubId) throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("request_id", UUID.randomUUID().toString());
        event.put("event_type", "CLUB_CREATED");
        event.set("object_ids", mapper.createObjectNode().put("club_id", clubId.toString()));
        event.set("payload", mapper.createObjectNode().put("name", "Club " + sequence));
        event.put("actor_id", UUID.randomUUID().toString());
        event.put("actor_key_id", keyId.toString());
        byte[] signature = Ed25519.sign(signer, json.bytes(ProjectionAudit.unsigned(event, json)));
        event.put("schema_version", 1);
        event.put("sequence", sequence);
        event.put("signature", Base64.getEncoder().encodeToString(signature));
        event.put("fabric_transaction_id", "tx-" + sequence);
        event.put("committed_at", "2026-11-0" + sequence + "T10:00:00Z");
        event.put("submitting_msp", "Org1MSP");
        return event;
    }

    @BeforeEach
    void ledgerWithThreeEvents() throws Exception {
        signer = generate();
        auditor = generate();
        keyId = UUID.randomUUID();
        events = new ArrayList<>();
        for (long sequence = 1; sequence <= 3; sequence++) {
            UUID club = UUID.randomUUID();
            clubs.add(club);
            events.add(signedEvent(sequence, club));
        }
    }

    private String auditKey() throws Exception {
        return Base64.getEncoder().encodeToString(Ed25519.rawPublic(auditor));
    }

    private LedgerCheckpoint.Checkpoint checkpoint() throws Exception {
        return LedgerCheckpoint.create(events, "instichain", "instichain", "2026-11-07T00:00:00Z", auditor, json);
    }

    private LedgerCheckpoint.Outcome verify(LedgerCheckpoint.Checkpoint checkpoint, List<JsonNode> ledger)
            throws Exception {
        return LedgerCheckpoint.verify(checkpoint, ledger, auditKey(), json).outcome();
    }

    @Test
    void untouchedLedgerVerifiesAndMayGrow() throws Exception {
        LedgerCheckpoint.Checkpoint checkpoint = checkpoint();
        assertEquals(LedgerCheckpoint.Outcome.VALID, verify(checkpoint, events));
        List<JsonNode> grown = new ArrayList<>(events);
        grown.add(signedEvent(4, UUID.randomUUID()));
        assertEquals(LedgerCheckpoint.Outcome.VALID, verify(checkpoint, grown));
    }

    @Test
    void modifiedEventIsDetected() throws Exception {
        LedgerCheckpoint.Checkpoint checkpoint = checkpoint();
        ((ObjectNode) events.get(1).path("payload")).put("name", "Renamed");
        assertEquals(LedgerCheckpoint.Outcome.HISTORY_CHANGED, verify(checkpoint, events));
    }

    @Test
    void reorderedEventsAreDetected() throws Exception {
        LedgerCheckpoint.Checkpoint checkpoint = checkpoint();
        JsonNode first = events.get(0);
        events.set(0, events.get(1));
        events.set(1, first);
        assertEquals(LedgerCheckpoint.Outcome.HISTORY_CHANGED, verify(checkpoint, events));
    }

    @Test
    void deletedOrTruncatedHistoryIsDetected() throws Exception {
        LedgerCheckpoint.Checkpoint checkpoint = checkpoint();
        assertEquals(LedgerCheckpoint.Outcome.TRUNCATED, verify(checkpoint, events.subList(0, 2)));
        List<JsonNode> gap = new ArrayList<>(events);
        gap.remove(1);
        gap.add(signedEvent(4, UUID.randomUUID()));
        assertEquals(LedgerCheckpoint.Outcome.HISTORY_CHANGED, verify(checkpoint, gap));
    }

    @Test
    void forgedOrSelfSignedCheckpointsAreRejected() throws Exception {
        LedgerCheckpoint.Checkpoint real = checkpoint();
        LedgerCheckpoint.Checkpoint edited = new LedgerCheckpoint.Checkpoint(1, real.channel(), real.chaincode(),
                real.sequence(), real.headRequestId(), LedgerCheckpoint.GENESIS, real.createdAt(),
                real.auditPublicKey(), real.signature());
        assertEquals(LedgerCheckpoint.Outcome.SIGNATURE_INVALID, verify(edited, events));
        // A forger signing with their own key fails because trust comes from the auditor, not the file.
        auditor = generate();
        LedgerCheckpoint.Checkpoint forged = checkpoint();
        assertEquals(LedgerCheckpoint.Outcome.UNTRUSTED_KEY,
                LedgerCheckpoint.verify(forged, events, Base64.getEncoder().encodeToString(
                        Ed25519.rawPublic(generate())), json).outcome());
    }

    private Map<UUID, ProjectionAudit.Receipt> receipts() {
        Map<UUID, ProjectionAudit.Receipt> receipts = new LinkedHashMap<>();
        for (JsonNode event : events) {
            UUID id = UUID.fromString(event.path("request_id").asText());
            receipts.put(id, new ProjectionAudit.Receipt(id, event.path("sequence").asLong(),
                    event.path("fabric_transaction_id").asText(), event.path("event_type").asText(),
                    event.path("object_ids").deepCopy(), event.path("payload").deepCopy(),
                    UUID.fromString(event.path("actor_id").asText()), keyId,
                    Base64.getDecoder().decode(event.path("signature").asText()),
                    OffsetDateTime.parse(event.path("committed_at").asText())));
        }
        return receipts;
    }

    private ProjectionAudit.Projection projection() throws Exception {
        byte[] publicKey = Ed25519.rawPublic(signer);
        return new ProjectionAudit.Projection() {
            @Override public byte[] publicKey(UUID id) { return id.equals(keyId) ? publicKey : null; }
            @Override public boolean clubExists(UUID clubId) { return clubs.contains(clubId); }
            @Override public boolean adminBootstrapped(UUID transactionId) { return true; }
            @Override public boolean appointmentGranted(UUID appointmentId, UUID transactionId) { return true; }
            @Override public boolean appointmentRevoked(UUID appointmentId, UUID transactionId) { return true; }
            @Override public boolean credentialIssued(UUID credentialId, UUID transactionId) { return true; }
            @Override public boolean credentialRevoked(UUID credentialId, UUID transactionId) { return true; }
            @Override public boolean keyRetired(UUID key, UUID transactionId) { return true; }
        };
    }

    private Set<ProjectionAudit.Kind> kinds(Map<UUID, ProjectionAudit.Receipt> receipts) throws Exception {
        Set<ProjectionAudit.Kind> kinds = new HashSet<>();
        ProjectionAudit.run(events, receipts, projection(), json).forEach(finding -> kinds.add(finding.kind()));
        return kinds;
    }

    @Test
    void consistentProjectionPasses() throws Exception {
        assertTrue(ProjectionAudit.run(events, receipts(), projection(), json).isEmpty());
    }

    @Test
    void missingAndChangedReceiptsAreReported() throws Exception {
        Map<UUID, ProjectionAudit.Receipt> receipts = receipts();
        UUID missing = UUID.fromString(events.get(0).path("request_id").asText());
        receipts.remove(missing);
        UUID changed = UUID.fromString(events.get(1).path("request_id").asText());
        ProjectionAudit.Receipt original = receipts.get(changed);
        ObjectNode payload = original.payload().deepCopy();
        payload.put("name", "Tampered");
        receipts.put(changed, new ProjectionAudit.Receipt(changed, original.sequence(), original.fabricTransactionId(),
                original.eventType(), original.objectIds(), payload, original.actorId(), original.actorKeyId(),
                original.signature(), original.committedAt()));
        Set<ProjectionAudit.Kind> kinds = kinds(receipts);
        assertTrue(kinds.contains(ProjectionAudit.Kind.MISSING_RECEIPT));
        assertTrue(kinds.contains(ProjectionAudit.Kind.CHANGED_RECEIPT));
    }

    @Test
    void receiptWithoutAFabricEventIsReported() throws Exception {
        Map<UUID, ProjectionAudit.Receipt> receipts = receipts();
        UUID orphan = UUID.randomUUID();
        receipts.put(orphan, new ProjectionAudit.Receipt(orphan, 9, "tx-9", "CLUB_CREATED",
                mapper.createObjectNode(), mapper.createObjectNode(), UUID.randomUUID(), keyId, null,
                OffsetDateTime.now()));
        assertEquals(Set.of(ProjectionAudit.Kind.RECEIPT_WITHOUT_EVENT), kinds(receipts));
    }

    @Test
    void invalidSignatureAndMissingProjectionAreReported() throws Exception {
        ((ObjectNode) events.get(2).path("payload")).put("name", "Forged after signing");
        clubs.clear();
        Set<ProjectionAudit.Kind> kinds = kinds(receipts());
        assertTrue(kinds.contains(ProjectionAudit.Kind.INVALID_SIGNATURE));
        assertTrue(kinds.contains(ProjectionAudit.Kind.MISSING_PROJECTION));
    }

    @Test
    void findingsNeverContainPayloadValues() throws Exception {
        ((ObjectNode) events.get(0).path("payload")).put("name", "Private detail");
        String report = ProjectionAudit.run(events, receipts(), projection(), json).toString();
        assertFalse(report.contains("Private detail"), report);
    }
}

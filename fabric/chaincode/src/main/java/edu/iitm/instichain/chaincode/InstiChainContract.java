package edu.iitm.instichain.chaincode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

@Contract(name = "InstiChainContract")
@Default
public final class InstiChainContract implements ContractInterface {
    static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final String EVENT_PREFIX = "event:";
    private static final String HEAD_KEY = "ledger:head";
    private static final String REVOCATION_PREFIX = "revocation:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> REVOCATION_SUBJECTS = Map.of(
            "ROLE_REVOKED", "appointment_id",
            "ACHIEVEMENT_REVOKED", "credential_id");

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String AppendEvent(Context context, String requestJson) {
        if (!"Org1MSP".equals(context.getClientIdentity().getMSPID())) {
            throw fail("Only an InstituteMSP identity may append events");
        }
        ObjectNode request = parseObject(requestJson, "request");
        for (String field : new String[]{
                "request_id", "event_type", "object_ids", "payload", "actor_id", "actor_key_id"}) {
            if (!request.hasNonNull(field)) {
                throw fail("request." + field + " is required");
            }
        }
        requireUuid(request.path("request_id").asText(), "request IDs must be lowercase UUIDs");
        requireUuid(request.path("actor_id").asText(), "request IDs must be lowercase UUIDs");
        requireUuid(request.path("actor_key_id").asText(), "request IDs must be lowercase UUIDs");
        if (!EVENT_TYPE.matcher(request.path("event_type").asText()).matches()) {
            throw fail("event_type must be uppercase snake case");
        }
        if (!request.path("object_ids").isObject() || !request.path("payload").isObject()) {
            throw fail("object_ids and payload must be JSON objects");
        }

        String requestId = request.path("request_id").asText();
        String key = EVENT_PREFIX + requestId;
        byte[] existingBytes = context.getStub().getState(key);
        if (hasBytes(existingBytes)) {
            ObjectNode existing = parseObject(new String(existingBytes, StandardCharsets.UTF_8), "stored event");
            if (!comparable(existing).equals(comparable(request))) {
                throw fail("request_id " + requestId + " already contains a different event");
            }
            return stringify(existing);
        }

        String eventType = request.path("event_type").asText();
        String subjectField = REVOCATION_SUBJECTS.get(eventType);
        String subjectId = null;
        if (subjectField != null) {
            subjectId = request.path("object_ids").path(subjectField).asText();
            requireUuid(subjectId, eventType + " requires object_ids." + subjectField + " as a lowercase UUID");
            if (hasBytes(context.getStub().getState(REVOCATION_PREFIX + subjectId))) {
                throw fail(subjectField + " " + subjectId + " is already revoked");
            }
            if (!hasBytes(context.getStub().getState(EVENT_PREFIX + subjectId))) {
                throw fail(subjectField + " " + subjectId + " has no granting event to revoke");
            }
        }

        byte[] headBytes = context.getStub().getState(HEAD_KEY);
        long sequence = hasBytes(headBytes)
                ? parseObject(new String(headBytes, StandardCharsets.UTF_8), "ledger head").path("sequence").asLong() + 1
                : 1;
        ObjectNode event = JSON.createObjectNode();
        event.put("schema_version", 1);
        event.put("sequence", sequence);
        copy(event, request, "request_id", "event_type", "object_ids", "payload", "actor_id", "actor_key_id");
        event.set("signature", request.hasNonNull("signature") ? request.get("signature") : JSON.nullNode());
        event.put("fabric_transaction_id", context.getStub().getTxId());
        event.put("committed_at", DateTimeFormatter.ISO_INSTANT.format(context.getStub().getTxTimestamp()));
        event.put("submitting_msp", context.getClientIdentity().getMSPID());

        byte[] bytes = stringify(event).getBytes(StandardCharsets.UTF_8);
        context.getStub().putState(key, bytes);
        context.getStub().putState(sequenceKey(sequence), bytes);
        if (subjectField != null) {
            ObjectNode revocation = JSON.createObjectNode();
            revocation.put("subject_field", subjectField);
            revocation.put("subject_id", subjectId);
            revocation.put("event_type", eventType);
            revocation.put("sequence", sequence);
            revocation.put("request_id", requestId);
            revocation.put("fabric_transaction_id", event.path("fabric_transaction_id").asText());
            revocation.put("revoked_at", event.path("committed_at").asText());
            context.getStub().putState(REVOCATION_PREFIX + subjectId,
                    stringify(revocation).getBytes(StandardCharsets.UTF_8));
        }
        ObjectNode head = JSON.createObjectNode();
        head.put("sequence", sequence);
        head.put("request_id", requestId);
        head.put("fabric_transaction_id", event.path("fabric_transaction_id").asText());
        context.getStub().putState(HEAD_KEY, stringify(head).getBytes(StandardCharsets.UTF_8));
        context.getStub().setEvent("InstiChainEvent", bytes);
        return stringify(event);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetEvent(Context context, String requestId) {
        requireUuid(requestId, "requestId must be a lowercase UUID");
        byte[] bytes = context.getStub().getState(EVENT_PREFIX + requestId);
        if (!hasBytes(bytes)) {
            throw fail("event " + requestId + " does not exist");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetHead(Context context) {
        byte[] bytes = context.getStub().getState(HEAD_KEY);
        return hasBytes(bytes) ? new String(bytes, StandardCharsets.UTF_8) : "{\"sequence\":0}";
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetRevocation(Context context, String subjectId) {
        requireUuid(subjectId, "subjectId must be a lowercase UUID");
        byte[] bytes = context.getStub().getState(REVOCATION_PREFIX + subjectId);
        if (!hasBytes(bytes)) {
            ObjectNode result = JSON.createObjectNode();
            result.put("subject_id", subjectId);
            result.put("revoked", false);
            return stringify(result);
        }
        ObjectNode result = parseObject(new String(bytes, StandardCharsets.UTF_8), "stored revocation");
        result.put("revoked", true);
        return stringify(result);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetRevocations(Context context) {
        List<ObjectNode> revocations = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> entries = context.getStub()
                .getStateByRange(REVOCATION_PREFIX, REVOCATION_PREFIX + "\uffff")) {
            for (KeyValue entry : entries) {
                revocations.add(parseObject(entry.getStringValue(), "stored revocation"));
            }
        }
        revocations.sort(Comparator.comparingLong(value -> value.path("sequence").asLong()));
        ArrayNode results = JSON.createArrayNode();
        revocations.forEach(results::add);
        return stringify(results);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetEvents(Context context, String startSequence, String limit) {
        long start = Math.max(1, parseLong(startSequence, 1));
        long count = Math.min(200, Math.max(1, parseLong(limit, 50)));
        ArrayNode results = JSON.createArrayNode();
        try (QueryResultsIterator<KeyValue> entries = context.getStub()
                .getStateByRange(sequenceKey(start), sequenceKey(start + count))) {
            for (KeyValue entry : entries) {
                results.add(parseObject(entry.getStringValue(), "stored event"));
            }
        }
        return stringify(results);
    }

    private static ObjectNode comparable(ObjectNode source) {
        ObjectNode result = JSON.createObjectNode();
        copy(result, source, "request_id", "event_type", "object_ids", "payload", "actor_id", "actor_key_id");
        result.set("signature", source.hasNonNull("signature") ? source.get("signature") : JSON.nullNode());
        return result;
    }

    private static void copy(ObjectNode target, ObjectNode source, String... fields) {
        for (String field : fields) {
            target.set(field, source.get(field));
        }
    }

    static ObjectNode parseObject(String value, String label) {
        try {
            JsonNode node = JSON.readTree(value);
            if (!node.isObject()) {
                throw fail(label + " must be a JSON object");
            }
            return (ObjectNode) node;
        } catch (JsonProcessingException exception) {
            throw fail(label + " must be valid JSON");
        }
    }

    private static void requireUuid(String value, String message) {
        if (!UUID.matcher(value).matches()) {
            throw fail(message);
        }
    }

    private static boolean hasBytes(byte[] value) {
        return value != null && value.length > 0;
    }

    private static String sequenceKey(long sequence) {
        return "sequence:" + String.format("%020d", sequence);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String stringify(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw fail("Could not serialize ledger data");
        }
    }

    private static ChaincodeException fail(String message) {
        return new ChaincodeException(message);
    }
}

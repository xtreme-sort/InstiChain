package edu.iitm.instichain.chaincode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hyperledger.fabric.contract.ClientIdentity;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.junit.jupiter.api.Test;

class InstiChainContractTest {
    @Test
    void parsesJsonObjects() {
        assertEquals("value", InstiChainContract.parseObject("{\"key\":\"value\"}", "request")
                .path("key").asText());
    }

    @Test
    void rejectsArraysAndMalformedJson() {
        assertThrows(ChaincodeException.class,
                () -> InstiChainContract.parseObject("[]", "request"));
        assertThrows(ChaincodeException.class,
                () -> InstiChainContract.parseObject("{", "request"));
    }

    @Test
    void appendsAnEventAndTreatsAnIdenticalRetryAsIdempotent() throws Exception {
        Context context = mock(Context.class);
        ChaincodeStub stub = mock(ChaincodeStub.class);
        ClientIdentity identity = mock(ClientIdentity.class);
        Map<String, byte[]> state = new HashMap<>();
        when(context.getStub()).thenReturn(stub);
        when(context.getClientIdentity()).thenReturn(identity);
        when(identity.getMSPID()).thenReturn("Org1MSP");
        when(stub.getState(any())).thenAnswer(call -> state.get(call.getArgument(0)));
        doAnswer(call -> {
            state.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(stub).putState(any(), any());
        when(stub.getTxId()).thenReturn("fabric-tx-1");
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("2026-01-02T03:04:05Z"));

        String request = """
                {"request_id":"b5411aa1-6c8b-4ee9-8905-a3ee2081fc4f",
                 "event_type":"CLUB_CREATED",
                 "object_ids":{"club_id":"dd340cc5-7758-46f2-8698-b97f76b52ef7"},
                 "payload":{"name":"Music Club"},
                 "actor_id":"7cb67e32-8c60-405c-b902-a9977c910c2c",
                 "actor_key_id":"2de79313-95c5-49c3-9459-fe3355fc016c",
                 "signature":"signed"}
                """;
        InstiChainContract contract = new InstiChainContract();
        String first = contract.AppendEvent(context, request);
        String retry = contract.AppendEvent(context, request);

        var event = new ObjectMapper().readTree(first);
        assertEquals(event, new ObjectMapper().readTree(retry));
        assertEquals(1, event.path("sequence").asLong());
        assertEquals("fabric-tx-1", event.path("fabric_transaction_id").asText());
        assertNotNull(state.get("event:b5411aa1-6c8b-4ee9-8905-a3ee2081fc4f"));
        assertNotNull(state.get("sequence:00000000000000000001"));
        assertNotNull(state.get("ledger:head"));
        verify(stub).setEvent("InstiChainEvent", first.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void indexesIssuedCredentialsAndRejectsSecondRevocation() {
        Context context = mock(Context.class);
        ChaincodeStub stub = mock(ChaincodeStub.class);
        ClientIdentity identity = mock(ClientIdentity.class);
        Map<String, byte[]> state = new HashMap<>();
        when(context.getStub()).thenReturn(stub);
        when(context.getClientIdentity()).thenReturn(identity);
        when(identity.getMSPID()).thenReturn("Org1MSP");
        when(stub.getState(any())).thenAnswer(call -> state.get(call.getArgument(0)));
        doAnswer(call -> { state.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(stub).putState(any(), any());
        when(stub.getTxId()).thenReturn("fabric-tx");
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("2026-01-02T03:04:05Z"));
        String credential = "a5411aa1-6c8b-4ee9-8905-a3ee2081fc4f";
        String issue = "b5411aa1-6c8b-4ee9-8905-a3ee2081fc4f";
        String actor = "7cb67e32-8c60-405c-b902-a9977c910c2c";
        String key = "2de79313-95c5-49c3-9459-fe3355fc016c";
        InstiChainContract contract = new InstiChainContract();
        contract.AppendEvent(context, """
                {"request_id":"%s","event_type":"ACHIEVEMENT_ISSUED",
                 "object_ids":{"credential_ids":["%s"]},"payload":{},
                 "actor_id":"%s","actor_key_id":"%s"}
                """.formatted(issue, credential, actor, key));
        assertNotNull(state.get("credential:" + credential));
        String revoke = "c5411aa1-6c8b-4ee9-8905-a3ee2081fc4f";
        String event = """
                {"request_id":"%s","event_type":"ACHIEVEMENT_REVOKED",
                 "object_ids":{"credential_id":"%s"},"payload":{"reason":"Incorrect award"},
                 "actor_id":"%s","actor_key_id":"%s"}
                """.formatted(revoke, credential, actor, key);
        contract.AppendEvent(context, event);
        assertTrue(new String(state.get("revocation:" + credential), StandardCharsets.UTF_8)
                .contains(revoke));
        assertEquals(contract.AppendEvent(context, event), contract.AppendEvent(context, event));
        assertThrows(ChaincodeException.class, () -> contract.AppendEvent(context, """
                {"request_id":"d5411aa1-6c8b-4ee9-8905-a3ee2081fc4f",
                 "event_type":"ACHIEVEMENT_REVOKED",
                 "object_ids":{"credential_id":"%s"},"payload":{},
                 "actor_id":"%s","actor_key_id":"%s"}
                """.formatted(credential, actor, key)));
    }

    private static Context ledger(Map<String, byte[]> state) {
        Context context = mock(Context.class);
        ChaincodeStub stub = mock(ChaincodeStub.class);
        ClientIdentity identity = mock(ClientIdentity.class);
        when(context.getStub()).thenReturn(stub);
        when(context.getClientIdentity()).thenReturn(identity);
        when(identity.getMSPID()).thenReturn("Org1MSP");
        when(stub.getState(any())).thenAnswer(call -> state.get(call.getArgument(0)));
        doAnswer(call -> { state.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(stub).putState(any(), any());
        when(stub.getTxId()).thenReturn("fabric-tx-key");
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("2026-01-02T03:04:05Z"));
        return context;
    }

    private static final String OLD_KEY = "11111111-1111-4111-8111-111111111111";
    private static final String NEW_KEY = "22222222-2222-4222-8222-222222222222";
    private static final String ADVISOR = "7cb67e32-8c60-405c-b902-a9977c910c2c";

    private static String event(String requestId, String type, String objects, String actorKey) {
        return "{\"request_id\":\"" + requestId + "\",\"event_type\":\"" + type + "\","
                + "\"object_ids\":" + objects + ",\"payload\":{},\"actor_id\":\"" + ADVISOR + "\","
                + "\"actor_key_id\":\"" + actorKey + "\",\"signature\":\"signed\"}";
    }

    private static String rotation(String requestId) {
        return event(requestId, "KEY_ROTATED", "{\"user_id\":\"" + ADVISOR + "\",\"old_key_id\":\""
                + OLD_KEY + "\",\"new_key_id\":\"" + NEW_KEY + "\"}", OLD_KEY);
    }

    @Test
    void rotatedKeyCannotSignNewEventsButRetriesStayIdempotent() {
        Map<String, byte[]> state = new HashMap<>();
        Context context = ledger(state);
        InstiChainContract contract = new InstiChainContract();
        String clubEvent = event("b5411aa1-6c8b-4ee9-8905-a3ee2081fc4f", "CLUB_CREATED",
                "{\"club_id\":\"dd340cc5-7758-46f2-8698-b97f76b52ef7\"}", OLD_KEY);
        String committed = contract.AppendEvent(context, clubEvent);
        contract.AppendEvent(context, rotation("33333333-3333-4333-8333-333333333333"));
        assertNotNull(state.get("revoked-key:" + OLD_KEY));
        // A retry of an event committed before the rotation still returns the stored event.
        assertEquals(committed, contract.AppendEvent(context, clubEvent));
        ChaincodeException error = assertThrows(ChaincodeException.class, () -> contract.AppendEvent(context,
                event("44444444-4444-4444-8444-444444444444", "CLUB_CREATED",
                        "{\"club_id\":\"55555555-5555-4555-8555-555555555555\"}", OLD_KEY)));
        assertTrue(error.getMessage().contains("has been revoked"));
        // The replacement key signs normally.
        contract.AppendEvent(context, event("66666666-6666-4666-8666-666666666666", "CLUB_CREATED",
                "{\"club_id\":\"77777777-7777-4777-8777-777777777777\"}", NEW_KEY));
    }

    @Test
    void rotationMustBeSignedByTheRetiredKey() {
        Context context = ledger(new HashMap<>());
        String forged = event("33333333-3333-4333-8333-333333333333", "KEY_ROTATED",
                "{\"user_id\":\"" + ADVISOR + "\",\"old_key_id\":\"" + OLD_KEY + "\",\"new_key_id\":\""
                        + NEW_KEY + "\"}", NEW_KEY);
        assertThrows(ChaincodeException.class, () -> new InstiChainContract().AppendEvent(context, forged));
    }

    @Test
    void compromisedKeyCannotBeRevokedTwiceOrReinstated() {
        Map<String, byte[]> state = new HashMap<>();
        Context context = ledger(state);
        InstiChainContract contract = new InstiChainContract();
        String admin = "99999999-9999-4999-8999-999999999999";
        String revoke = event("33333333-3333-4333-8333-333333333333", "KEY_REVOKED",
                "{\"user_id\":\"" + ADVISOR + "\",\"key_id\":\"" + OLD_KEY + "\"}", admin);
        contract.AppendEvent(context, revoke);
        assertThrows(ChaincodeException.class, () -> contract.AppendEvent(context, event(
                "44444444-4444-4444-8444-444444444444", "KEY_REVOKED",
                "{\"user_id\":\"" + ADVISOR + "\",\"key_id\":\"" + OLD_KEY + "\"}", admin)));
        ChaincodeException reinstate = assertThrows(ChaincodeException.class, () -> contract.AppendEvent(context,
                event("55555555-5555-4555-8555-555555555555", "KEY_RECOVERED", "{\"user_id\":\"" + ADVISOR
                        + "\",\"revoked_key_ids\":[],\"new_key_id\":\"" + OLD_KEY + "\"}", admin)));
        assertTrue(reinstate.getMessage().contains("cannot be reinstated"));
    }

    @Test
    void recoveryRetiresEveryListedKey() {
        Map<String, byte[]> state = new HashMap<>();
        Context context = ledger(state);
        new InstiChainContract().AppendEvent(context, event("33333333-3333-4333-8333-333333333333",
                "KEY_RECOVERED", "{\"user_id\":\"" + ADVISOR + "\",\"revoked_key_ids\":[\"" + OLD_KEY
                        + "\"],\"new_key_id\":\"" + NEW_KEY + "\"}", "99999999-9999-4999-8999-999999999999"));
        assertNotNull(state.get("revoked-key:" + OLD_KEY));
        assertEquals(null, state.get("revoked-key:" + NEW_KEY));
    }
}

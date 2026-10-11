package edu.iitm.instichain.chaincode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}

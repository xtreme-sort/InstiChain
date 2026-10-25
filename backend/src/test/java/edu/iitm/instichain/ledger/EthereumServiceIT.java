package edu.iitm.instichain.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs against a live chain with a deployed InstiChainLedger. Start one with
 * {@code make ethereum-node} and {@code make ethereum-deploy}, then set
 * INSTICHAIN_ETHEREUM_IT_CONTRACT (and optionally INSTICHAIN_ETHEREUM_IT_RPC / _KEY).
 */
@EnabledIfEnvironmentVariable(named = "INSTICHAIN_ETHEREUM_IT_CONTRACT", matches = "0x[0-9a-fA-F]{40}")
class EthereumServiceIT {
    // Hardhat's public, well-known development account #0. Never use it on a real network.
    private static final String HARDHAT_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private final ObjectMapper mapper = new ObjectMapper();

    private EthereumService service() {
        String rpc = System.getenv().getOrDefault("INSTICHAIN_ETHEREUM_IT_RPC", "http://127.0.0.1:8545");
        String key = System.getenv().getOrDefault("INSTICHAIN_ETHEREUM_IT_KEY", HARDHAT_KEY);
        InstiChainProperties properties = new InstiChainProperties("test", "http://127.0.0.1:5173",
                15, 20, 30, null, "ethereum", null,
                new InstiChainProperties.Ethereum(rpc, 31337,
                        System.getenv("INSTICHAIN_ETHEREUM_IT_CONTRACT"), key),
                null, null, null);
        return new EthereumService(properties, new CanonicalJson(mapper), null);
    }

    private ObjectNode document(UUID requestId, String type, String objectField, UUID objectId) {
        ObjectNode document = mapper.createObjectNode();
        document.put("request_id", requestId.toString());
        document.put("event_type", type);
        document.putObject("object_ids").put(objectField, objectId.toString());
        document.putObject("payload").put("reason", "integration test");
        document.put("actor_id", UUID.randomUUID().toString());
        document.put("actor_key_id", UUID.randomUUID().toString());
        document.put("signature", "c2lnbmF0dXJl");
        return document;
    }

    @Test
    void appendsRetriesRejectsConflictsAndIndexesRevocations() {
        EthereumService ledger = service();
        long before = ledger.head().path("sequence").asLong();

        UUID grant = UUID.randomUUID();
        ObjectNode granted = document(grant, "ROLE_GRANTED", "appointment_id", grant);
        LedgerClient.Receipt first = ledger.submit(granted);
        assertEquals(before + 1, first.event().path("sequence").asLong());
        assertTrue(first.submittedTransactionId().matches("0x[0-9a-f]{64}"));
        assertEquals(granted.path("payload"), first.event().path("payload"));

        LedgerClient.Receipt retry = ledger.submit(granted.deepCopy());
        assertEquals(first.submittedTransactionId(), retry.submittedTransactionId());
        assertEquals(before + 1, ledger.head().path("sequence").asLong());

        ObjectNode conflicting = granted.deepCopy();
        conflicting.put("signature", "ZGlmZmVyZW50");
        assertThrows(FabricUnavailableException.class, () -> ledger.submit(conflicting));

        UUID revocation = UUID.randomUUID();
        ledger.submit(document(revocation, "ROLE_REVOKED", "appointment_id", grant));
        assertThrows(FabricUnavailableException.class, () -> ledger.submit(
                document(UUID.randomUUID(), "ROLE_REVOKED", "appointment_id", grant)));

        boolean indexed = false;
        for (JsonNode entry : ledger.revocations()) {
            indexed |= entry.path("subject_id").asText().equals(grant.toString())
                    && entry.path("request_id").asText().equals(revocation.toString());
        }
        assertTrue(indexed);
        assertNull(ledger.event(UUID.randomUUID()));
    }

    @Test
    void revokesACredentialIssuedThroughAnAchievementEvent() {
        EthereumService ledger = service();
        UUID credential = UUID.randomUUID();
        ObjectNode issued = document(UUID.randomUUID(), "ACHIEVEMENT_ISSUED", "result_id", UUID.randomUUID());
        ((ObjectNode) issued.path("object_ids")).putArray("credential_ids").add(credential.toString());
        ledger.submit(issued);

        ledger.submit(document(UUID.randomUUID(), "ACHIEVEMENT_REVOKED", "credential_id", credential));
        assertThrows(FabricUnavailableException.class, () -> ledger.submit(
                document(UUID.randomUUID(), "ACHIEVEMENT_REVOKED", "credential_id", credential)));
    }
}

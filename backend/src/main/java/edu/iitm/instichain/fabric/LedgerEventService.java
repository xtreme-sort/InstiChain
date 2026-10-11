package edu.iitm.instichain.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.config.InstiChainProperties;
import java.security.PrivateKey;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LedgerEventService {
    public static final long LEDGER_LOCK = 50130001L;
    private final JdbcTemplate jdbc;
    private final CanonicalJson json;
    private final FabricService fabric;
    private final InstiChainProperties properties;

    public LedgerEventService(JdbcTemplate jdbc, CanonicalJson json, FabricService fabric,
                              InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.fabric = fabric;
        this.properties = properties;
    }

    public void lock() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> { }, LEDGER_LOCK);
    }

    public Event appendAdmin(Object document, UUID keyId, byte[] storedPublicKey) {
        try {
            PrivateKey privateKey = Ed25519.readPrivateKey(properties.adminSigningKeyFile());
            if (!Ed25519.matches(privateKey, storedPublicKey)) {
                throw new FabricUnavailableException("Administrator signing key is mismatched");
            }
            return append(document, Ed25519.sign(privateKey, json.bytes(document)), keyId);
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new FabricUnavailableException("Administrator signing key is unavailable", error);
        }
    }

    public Event append(Object document, byte[] signature, UUID keyId) {
        JsonNode unsigned = json.tree(document);
        ObjectNode submitted = unsigned.deepCopy();
        submitted.put("signature", Base64.getEncoder().encodeToString(signature));
        FabricService.Receipt receipt = fabric.submit(submitted);
        JsonNode event = receipt.event();
        for (String field : new String[]{"request_id", "event_type", "object_ids", "payload",
                "actor_id", "actor_key_id"}) {
            if (!event.path(field).equals(unsigned.path(field))) {
                throw new FabricUnavailableException("Fabric returned a mismatched event");
            }
        }
        UUID requestId = UUID.fromString(event.path("request_id").asText());
        OffsetDateTime committedAt = OffsetDateTime.parse(event.path("committed_at").asText());
        jdbc.update("""
                INSERT INTO fabric_transactions(
                    request_id, source, sequence, fabric_transaction_id,
                    submitted_transaction_id, channel_name, chaincode_name, block_number,
                    validation_code, schema_version, event_type, object_ids, payload,
                    actor_id, actor_key_id, signature, committed_at)
                VALUES (?, 'fabric', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                """, requestId, event.path("sequence").asLong(),
                event.path("fabric_transaction_id").asText(), receipt.submittedTransactionId(),
                properties.fabric().channelName(), properties.fabric().chaincodeName(),
                receipt.blockNumber(), receipt.validationCode(), event.path("schema_version").asInt(),
                event.path("event_type").asText(), event.path("object_ids").toString(),
                event.path("payload").toString(), UUID.fromString(event.path("actor_id").asText()),
                keyId, signature, committedAt);
        return new Event(requestId, committedAt, event);
    }

    public record Event(UUID requestId, OffsetDateTime committedAt, JsonNode body) {}
}

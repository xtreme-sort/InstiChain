package edu.iitm.instichain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.fabric.FabricService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final int PAGE = 200;
    private final FabricService fabric;
    private final JdbcTemplate jdbc;
    private final CanonicalJson json;
    private final InstiChainProperties properties;

    public AuditService(FabricService fabric, JdbcTemplate jdbc, CanonicalJson json,
                        InstiChainProperties properties) {
        this.fabric = fabric;
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
    }

    public String channel() { return properties.fabric().channelName(); }
    public String chaincode() { return properties.fabric().chaincodeName(); }
    public CanonicalJson json() { return json; }

    /** Every committed event in sequence order, read directly from Fabric. */
    public List<JsonNode> fabricEvents() {
        List<JsonNode> events = new ArrayList<>();
        for (long start = 1; ; start += PAGE) {
            JsonNode page = fabric.evaluate("GetEvents", Long.toString(start), Integer.toString(PAGE));
            page.forEach(events::add);
            if (page.size() < PAGE) return events;
        }
    }

    public Map<UUID, ProjectionAudit.Receipt> receipts() {
        Map<UUID, ProjectionAudit.Receipt> receipts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT request_id, sequence, fabric_transaction_id, event_type, object_ids::text AS objects,
                       payload::text AS payload, actor_id, actor_key_id, signature, committed_at
                FROM fabric_transactions WHERE source = 'fabric' ORDER BY sequence
                """, row -> {
            UUID id = row.getObject("request_id", UUID.class);
            receipts.put(id, new ProjectionAudit.Receipt(id, row.getLong("sequence"),
                    row.getString("fabric_transaction_id"), row.getString("event_type"),
                    json.parse(row.getString("objects").getBytes(StandardCharsets.UTF_8)),
                    json.parse(row.getString("payload").getBytes(StandardCharsets.UTF_8)),
                    row.getObject("actor_id", UUID.class), row.getObject("actor_key_id", UUID.class),
                    row.getBytes("signature"), row.getObject("committed_at", OffsetDateTime.class)));
        });
        return receipts;
    }

    public ProjectionAudit.Projection projection() {
        return new ProjectionAudit.Projection() {
            private boolean exists(String sql, Object... arguments) {
                return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(" + sql + ")", Boolean.class, arguments));
            }
            @Override public byte[] publicKey(UUID keyId) {
                List<byte[]> keys = jdbc.query("SELECT public_key FROM authority_keys WHERE id = ?",
                        (row, number) -> row.getBytes("public_key"), keyId);
                return keys.isEmpty() ? null : keys.getFirst();
            }
            @Override public boolean clubExists(UUID clubId) {
                return exists("SELECT 1 FROM clubs WHERE id = ?", clubId);
            }
            @Override public boolean adminBootstrapped(UUID transactionId) {
                return exists("SELECT 1 FROM administrator WHERE bootstrap_transaction_id = ?", transactionId);
            }
            @Override public boolean appointmentGranted(UUID appointmentId, UUID transactionId) {
                return exists("SELECT 1 FROM appointments WHERE id = ? AND grant_transaction_id = ?",
                        appointmentId, transactionId);
            }
            @Override public boolean appointmentRevoked(UUID appointmentId, UUID transactionId) {
                return exists("SELECT 1 FROM appointments WHERE id = ? AND revocation_transaction_id = ?",
                        appointmentId, transactionId);
            }
            @Override public boolean credentialIssued(UUID credentialId, UUID transactionId) {
                return exists("SELECT 1 FROM credentials WHERE id = ? AND issuance_transaction_id = ?",
                        credentialId, transactionId);
            }
            @Override public boolean credentialRevoked(UUID credentialId, UUID transactionId) {
                return exists("SELECT 1 FROM credentials WHERE id = ? AND revocation_transaction_id = ?",
                        credentialId, transactionId);
            }
            @Override public boolean keyRetired(UUID keyId, UUID transactionId) {
                return exists("SELECT 1 FROM authority_keys WHERE id = ? AND revocation_transaction_id = ?",
                        keyId, transactionId);
            }
        };
    }

    public List<ProjectionAudit.Finding> audit() {
        return ProjectionAudit.run(fabricEvents(), receipts(), projection(), json);
    }
}

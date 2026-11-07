package edu.iitm.instichain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.fabric.FabricService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ProjectionRebuild.Plan rebuildPlan() {
        return ProjectionRebuild.plan(fabricEvents(), new ProjectionRebuild.State() {
            private boolean exists(String sql, UUID id) {
                return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(" + sql + ")", Boolean.class, id));
            }
            @Override public boolean receiptExists(UUID requestId) {
                return exists("SELECT 1 FROM fabric_transactions WHERE request_id = ?", requestId);
            }
            @Override public boolean userExists(UUID userId) { return exists("SELECT 1 FROM users WHERE id = ?", userId); }
            @Override public boolean clubExists(UUID clubId) { return exists("SELECT 1 FROM clubs WHERE id = ?", clubId); }
            @Override public Boolean appointmentRevoked(UUID appointmentId) {
                List<Boolean> rows = jdbc.queryForList(
                        "SELECT revoked_at IS NOT NULL FROM appointments WHERE id = ?", Boolean.class, appointmentId);
                return rows.isEmpty() ? null : rows.getFirst();
            }
        });
    }

    /**
     * Applies a plan in one transaction. Every statement inserts only when absent or fills a
     * missing revocation, so re-running it is harmless and existing rows are never altered.
     */
    @Transactional
    public int applyRebuild(ProjectionRebuild.Plan plan) {
        int changed = 0;
        for (ProjectionRebuild.Action action : plan.actions()) {
            JsonNode event = action.event();
            JsonNode payload = event.path("payload");
            OffsetDateTime committed = OffsetDateTime.parse(event.path("committed_at").asText());
            UUID actor = UUID.fromString(event.path("actor_id").asText());
            changed += switch (action.operation()) {
                // Fabric world state only holds validly committed transactions, so the code is 0.
                case INSERT_RECEIPT -> jdbc.update("""
                        INSERT INTO fabric_transactions(request_id, source, sequence, fabric_transaction_id,
                            channel_name, chaincode_name, validation_code, schema_version, event_type,
                            object_ids, payload, actor_id, actor_key_id, signature, committed_at)
                        VALUES (?, 'fabric', ?, ?, ?, ?, 0, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                        ON CONFLICT (request_id) DO NOTHING
                        """, action.requestId(), event.path("sequence").asLong(),
                        event.path("fabric_transaction_id").asText(), channel(), chaincode(),
                        event.path("schema_version").asInt(1), event.path("event_type").asText(),
                        event.path("object_ids").toString(), payload.toString(), actor,
                        UUID.fromString(event.path("actor_key_id").asText()),
                        event.path("signature").isTextual()
                                ? Base64.getDecoder().decode(event.path("signature").asText()) : null, committed);
                case INSERT_CLUB -> jdbc.update("""
                        INSERT INTO clubs(id, slug, name, description, created_by_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, action.subjectId(), payload.path("slug").asText(), payload.path("name").asText(),
                        payload.path("description").isTextual() ? payload.path("description").asText() : null,
                        actor, committed);
                case INSERT_APPOINTMENT -> {
                    String key = "ADVISOR_APPOINTED".equals(event.path("event_type").asText())
                            ? payload.path("key_id").asText() : event.path("actor_key_id").asText();
                    List<UUID> keys = jdbc.queryForList("SELECT id FROM authority_keys WHERE id = ?::uuid",
                            UUID.class, key);
                    // verification_reference stays NULL: it was a private note, never on Fabric.
                    yield jdbc.update("""
                            INSERT INTO appointments(id, club_id, user_id, position, granted_by_id, advisor_key_id,
                                starts_at, ends_at, grant_transaction_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                            """, action.subjectId(), UUID.fromString(event.path("object_ids").path("club_id").asText()),
                            UUID.fromString(event.path("object_ids").path("user_id").asText()),
                            payload.path("position").asText(), actor, keys.isEmpty() ? null : keys.getFirst(),
                            ProjectionRebuild.time(payload, "starts_at"), ProjectionRebuild.time(payload, "ends_at"),
                            action.requestId(), committed);
                }
                case RECORD_REVOCATION -> jdbc.update("""
                        UPDATE appointments SET revoked_at = ?, revocation_transaction_id = ?
                        WHERE id = ? AND revoked_at IS NULL
                        """, payload.hasNonNull("revoked_at") ? ProjectionRebuild.time(payload, "revoked_at") : committed,
                        action.requestId(), action.subjectId());
            };
        }
        return changed;
    }
}

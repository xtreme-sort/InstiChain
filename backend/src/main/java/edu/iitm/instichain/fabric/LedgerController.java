package edu.iitm.instichain.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    private final FabricService fabric;
    private final JdbcTemplate jdbc;
    private final InstiChainProperties properties;

    public LedgerController(FabricService fabric, JdbcTemplate jdbc,
                            InstiChainProperties properties) {
        this.fabric = fabric;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "channel", properties.fabric().channelName(),
                "chaincode", properties.fabric().chaincodeName(), "head", evaluate("GetHead"));
    }

    @GetMapping("/events")
    JsonNode events(@RequestParam(name = "start_sequence", defaultValue = "1") int start,
                    @RequestParam(defaultValue = "50") int limit) {
        if (start < 1 || limit < 1 || limit > 200) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "start must be positive and limit must be between 1 and 200");
        return evaluate("GetEvents", Integer.toString(start), Integer.toString(limit));
    }

    @GetMapping("/events/{requestId}")
    JsonNode event(@PathVariable UUID requestId) { return evaluate("GetEvent", requestId.toString()); }

    @GetMapping("/fabric-revocations")
    JsonNode fabricRevocations() { return evaluate("GetRevocations"); }

    @GetMapping("/revocations")
    Map<String, Object> revocations(@RequestParam(required = false) String subject,
                                    @RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    HttpServletResponse response) {
        if (subject != null && !subject.equals("appointment") && !subject.equals("credential"))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid revocation subject");
        if (limit < 1 || limit > 500 || offset < 0)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid pagination");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String union = union(subject);
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + union + ") rows", Long.class);
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM (" + union
                        + ") rows ORDER BY revoked_at DESC, subject_id LIMIT ? OFFSET ?",
                (result, row) -> Map.of("subject_type", result.getString("subject_type"),
                        "subject_id", result.getObject("subject_id", UUID.class),
                        "revoked_at", result.getObject("revoked_at", OffsetDateTime.class),
                        "revocation_transaction_id",
                        result.getObject("revocation_transaction_id", UUID.class)), limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checked_at", now()); body.put("total", total); body.put("limit", limit);
        body.put("offset", offset); body.put("revocations", rows);
        return body;
    }

    @GetMapping("/revocations/{subjectType}/{subjectId}")
    Map<String, Object> status(@PathVariable String subjectType, @PathVariable UUID subjectId,
                               HttpServletResponse response) {
        if (!subjectType.equals("appointment") && !subjectType.equals("credential"))
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM (" + union(subjectType)
                + ") rows WHERE subject_id = ?", subjectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject_type", subjectType); result.put("subject_id", subjectId);
        result.put("checked_at", now()); result.put("revoked", !rows.isEmpty());
        result.put("revoked_at", rows.isEmpty() ? null : rows.getFirst().get("revoked_at"));
        result.put("revocation_transaction_id", rows.isEmpty() ? null
                : rows.getFirst().get("revocation_transaction_id"));
        return result;
    }

    private JsonNode evaluate(String transaction, String... arguments) {
        try { return fabric.evaluate(transaction, arguments); }
        catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The Fabric ledger is unavailable.");
        }
    }

    private String union(String subject) {
        String appointments = "SELECT 'appointment' AS subject_type, id AS subject_id, revoked_at, "
                + "revocation_transaction_id FROM appointments WHERE revoked_at IS NOT NULL";
        String credentials = "SELECT 'credential' AS subject_type, id AS subject_id, revoked_at, "
                + "revocation_transaction_id FROM credentials WHERE revoked_at IS NOT NULL";
        if ("appointment".equals(subject)) return appointments;
        if ("credential".equals(subject)) return credentials;
        return appointments + " UNION ALL " + credentials;
    }

    private OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
}

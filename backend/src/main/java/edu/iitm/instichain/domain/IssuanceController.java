package edu.iitm.instichain.domain;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.common.UuidV5;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import edu.iitm.instichain.fabric.LedgerEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clubs/{clubId}/competitions/{competitionId}/results")
public class IssuanceController {
    private static final Set<String> ADVISOR = Set.of("faculty_advisor");
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final LedgerEventService ledger;
    private final CanonicalJson json;

    public IssuanceController(JdbcTemplate jdbc, AccessService access,
                              LedgerEventService ledger, CanonicalJson json) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
        this.json = json;
    }

    record SignedDocument(@NotNull JsonNode document,
                          @NotBlank @Size(max = 88) String signature) {}
    record AdvisorKey(UUID id, UUID userId, byte[] publicKey) {}
    record Submission(UUID id, UUID resultId, int version, String award, String proofDigest,
                      UUID competitionId, String competitionTitle, OffsetDateTime startsAt,
                      OffsetDateTime endsAt) {}
    record Claim(UUID credentialId, UUID recipientId, UUID supersedesId) {}
    record Credential(UUID id, UUID recipientId, String award, String proofDigest,
                      int submissionVersion, UUID supersedesId, UUID transactionId,
                      OffsetDateTime issuedAt) {}

    @PostMapping("/{resultId}/versions/{version}/issuance/prepare")
    Map<String, Object> prepare(@PathVariable UUID clubId, @PathVariable UUID competitionId,
                                @PathVariable UUID resultId, @PathVariable int version,
                                HttpServletRequest request) {
        AccessService.ClubAuthority advisor = access.club(request, clubId, ADVISOR);
        Map<String, Object> document = document(advisor, clubId, competitionId, resultId, version);
        return Map.of("document", document,
                "signing_payload", Base64.getEncoder().encodeToString(json.bytes(document)));
    }

    @PostMapping("/{resultId}/versions/{version}/issuance")
    @Transactional
    org.springframework.http.ResponseEntity<Map<String, Object>> issue(
            @PathVariable UUID clubId, @PathVariable UUID competitionId,
            @PathVariable UUID resultId, @PathVariable int version,
            @Valid @RequestBody SignedDocument body, HttpServletRequest request) {
        AccessService.ClubAuthority advisor = access.club(request, clubId, ADVISOR);
        Map<String, Object> expected = document(advisor, clubId, competitionId, resultId, version);
        if (!json.tree(expected).equals(body.document())) {
            throw invalid("The signed issuance does not match the frozen result submission.");
        }
        AdvisorKey key = advisorKey(advisor);
        byte[] signature = decodeSignature(body.signature());
        if (!Ed25519.verify(key.publicKey(), json.bytes(body.document()), signature)) {
            throw invalid("The advisor signature is invalid.");
        }
        UUID requestId = UUID.fromString(body.document().path("request_id").asText());
        List<Credential> existing = credentials(resultId, version);
        if (!existing.isEmpty()) return org.springframework.http.ResponseEntity.ok(response(existing));
        LedgerEventService.Event event;
        try {
            event = ledger.append(body.document(), signature, key.id());
        } catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Fabric ledger is unavailable; no credentials were issued.");
        }
        JsonNode payload = body.document().path("payload");
        for (JsonNode claim : payload.path("claims")) {
            UUID credentialId = UUID.fromString(claim.path("credential_id").asText());
            UUID recipientId = UUID.fromString(claim.path("recipient_id").asText());
            UUID supersedes = claim.path("supersedes_id").isNull() ? null
                    : UUID.fromString(claim.path("supersedes_id").asText());
            jdbc.update("""
                    INSERT INTO credentials(id, competition_id, recipient_id, advisor_id, award,
                        result_id, submission_version, proof_digest, issuance_transaction_id,
                        issued_at, supersedes_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, credentialId, competitionId, recipientId, advisor.user().id(),
                    payload.path("award").asText(), resultId, version,
                    payload.path("proof_digest").asText(), requestId, event.committedAt(), supersedes);
        }
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(response(credentials(resultId, version)));
    }

    private Map<String, Object> document(AccessService.ClubAuthority authority, UUID clubId,
                                         UUID competitionId, UUID resultId, int version) {
        AdvisorKey key = advisorKey(authority);
        Submission submission = submission(clubId, competitionId, resultId, version);
        List<UUID> recipients = jdbc.queryForList("""
                SELECT rr.recipient_id FROM result_recipients rr
                WHERE rr.submission_id = ? ORDER BY rr.recipient_id
                """, UUID.class, submission.id());
        if (recipients.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,
                "The result submission has no recipients.");
        Integer previousVersion = jdbc.queryForObject("""
                SELECT max(submission_version) FROM credentials
                WHERE result_id = ? AND submission_version < ?
                """, Integer.class, resultId, version);
        Map<UUID, UUID> previous = new java.util.HashMap<>();
        List<UUID> superseded = new ArrayList<>();
        if (previousVersion != null) {
            jdbc.query("""
                    SELECT id, recipient_id FROM credentials
                    WHERE result_id = ? AND submission_version = ? ORDER BY recipient_id
                    """, result -> {
                UUID id = result.getObject("id", UUID.class);
                previous.put(result.getObject("recipient_id", UUID.class), id);
                superseded.add(id);
            }, resultId, previousVersion);
        }
        List<Map<String, Object>> claims = new ArrayList<>();
        List<UUID> credentialIds = new ArrayList<>();
        for (UUID recipient : recipients) {
            UUID credentialId = UuidV5.url("instichain:credential:" + submission.id() + ":" + recipient);
            credentialIds.add(credentialId);
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("credential_id", credentialId);
            claim.put("recipient_id", recipient);
            claim.put("supersedes_id", previous.get(recipient));
            claims.add(claim);
        }
        UUID requestId = UuidV5.url("instichain:achievement-issued:" + submission.id());
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("club_id", clubId); objects.put("competition_id", competitionId);
        objects.put("result_id", resultId); objects.put("submission_id", submission.id());
        objects.put("credential_ids", credentialIds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submission_version", version); payload.put("award", submission.award());
        payload.put("proof_digest", submission.proofDigest());
        payload.put("competition_title", submission.competitionTitle());
        payload.put("competition_starts_at", submission.startsAt());
        payload.put("competition_ends_at", submission.endsAt());
        payload.put("superseded_credential_ids", superseded); payload.put("claims", claims);
        return AdminController.action(requestId, "ACHIEVEMENT_ISSUED", objects, payload,
                authority.user().id(), key.id());
    }

    private Submission submission(UUID clubId, UUID competitionId, UUID resultId, int version) {
        List<Submission> rows = jdbc.query("""
                SELECT s.id, s.result_id, s.version, s.award, s.proof_digest,
                       c.id AS competition_id, c.title, c.starts_at, c.ends_at
                FROM result_submissions s
                JOIN competition_results r ON r.id = s.result_id
                JOIN competitions c ON c.id = r.competition_id
                WHERE c.club_id = ? AND c.id = ? AND r.id = ? AND s.version = ?
                """, (result, row) -> new Submission(result.getObject("id", UUID.class),
                result.getObject("result_id", UUID.class), result.getInt("version"),
                result.getString("award"), result.getString("proof_digest"),
                result.getObject("competition_id", UUID.class), result.getString("title"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class)), clubId, competitionId, resultId, version);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Result submission not found.");
        return rows.getFirst();
    }

    private AdvisorKey advisorKey(AccessService.ClubAuthority authority) {
        UUID id = authority.appointment().advisorKeyId();
        List<AdvisorKey> keys = id == null ? List.of() : jdbc.query("""
                SELECT id, user_id, public_key FROM authority_keys
                WHERE id = ? AND purpose = 'advisor' AND revoked_at IS NULL
                """, (result, row) -> new AdvisorKey(result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class), result.getBytes("public_key")), id);
        if (keys.isEmpty() || !keys.getFirst().userId().equals(authority.user().id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Advisor signing authority is inactive.");
        }
        return keys.getFirst();
    }

    private List<Credential> credentials(UUID resultId, int version) {
        return jdbc.query("""
                SELECT id, recipient_id, award, proof_digest, submission_version, supersedes_id,
                       issuance_transaction_id, issued_at FROM credentials
                WHERE result_id = ? AND submission_version = ? ORDER BY recipient_id
                """, (result, row) -> new Credential(result.getObject("id", UUID.class),
                result.getObject("recipient_id", UUID.class), result.getString("award"),
                result.getString("proof_digest"), result.getInt("submission_version"),
                result.getObject("supersedes_id", UUID.class),
                result.getObject("issuance_transaction_id", UUID.class),
                result.getObject("issued_at", OffsetDateTime.class)), resultId, version);
    }

    private Map<String, Object> response(List<Credential> credentials) {
        if (credentials.isEmpty()) throw new IllegalStateException("No credentials were created");
        List<Map<String, Object>> rows = credentials.stream().map(credential -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", credential.id()); row.put("recipient_id", credential.recipientId());
            row.put("award", credential.award()); row.put("proof_digest", credential.proofDigest());
            row.put("submission_version", credential.submissionVersion());
            row.put("supersedes_id", credential.supersedesId());
            return row;
        }).toList();
        return Map.of("transaction_id", credentials.getFirst().transactionId(),
                "issued_at", credentials.getFirst().issuedAt(), "credentials", rows);
    }

    private static byte[] decodeSignature(String encoded) {
        try {
            byte[] signature = Base64.getDecoder().decode(encoded);
            if (signature.length != 64) throw new IllegalArgumentException();
            return signature;
        } catch (IllegalArgumentException error) {
            throw invalid("Provide a 64-byte Ed25519 signature");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

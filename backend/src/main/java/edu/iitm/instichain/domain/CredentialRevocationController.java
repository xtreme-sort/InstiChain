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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clubs/{clubId}/credentials/{credentialId}/revocations")
public class CredentialRevocationController {
    private static final Set<String> ADVISOR = Set.of("faculty_advisor");
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final LedgerEventService ledger;
    private final CanonicalJson json;

    public CredentialRevocationController(JdbcTemplate jdbc, AccessService access,
                                          LedgerEventService ledger, CanonicalJson json) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
        this.json = json;
    }

    record Draft(@NotBlank @Size(max = 500) String reason) {}
    record SignedDocument(@NotNull JsonNode document, @NotBlank @Size(max = 88) String signature) {}
    record Key(UUID id, UUID userId, byte[] publicKey) {}
    record Credential(UUID id, UUID clubId, UUID recipientId, UUID issuanceId,
                      OffsetDateTime issuedAt, OffsetDateTime revokedAt, UUID revocationId) {}

    @PostMapping("/prepare")
    Map<String, Object> prepare(@PathVariable UUID clubId, @PathVariable UUID credentialId,
                                @Valid @RequestBody Draft body, HttpServletRequest request) {
        AccessService.ClubAuthority advisor = access.club(request, clubId, ADVISOR);
        Credential credential = credential(clubId, credentialId, false);
        if (credential.revokedAt() != null) throw conflict();
        Key key = advisorKey(advisor);
        OffsetDateTime now = now();
        Map<String, Object> document = document(advisor, key, credential, body.reason().strip(), now);
        return Map.of("document", document,
                "signing_payload", Base64.getEncoder().encodeToString(json.bytes(document)));
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> revoke(@PathVariable UUID clubId, @PathVariable UUID credentialId,
                                                @Valid @RequestBody SignedDocument body,
                                                HttpServletRequest request) {
        AccessService.ClubAuthority advisor = access.club(request, clubId, ADVISOR);
        Credential credential = credential(clubId, credentialId, true);
        Key key = advisorKey(advisor);
        JsonNode payload = body.document().path("payload");
        String reason = payload.path("reason").asText("").strip();
        if (reason.isEmpty() || reason.length() > 500) throw invalid("A bounded revocation reason is required.");
        OffsetDateTime preparedAt;
        try { preparedAt = OffsetDateTime.parse(payload.path("prepared_at").asText()); }
        catch (RuntimeException error) { throw invalid("Invalid revocation timestamp."); }
        Map<String, Object> expected = document(advisor, key, credential, reason, preparedAt);
        if (!json.tree(expected).equals(body.document())) {
            throw invalid("The signed revocation does not match this credential and advisor.");
        }
        byte[] signature;
        try { signature = Base64.getDecoder().decode(body.signature()); }
        catch (IllegalArgumentException error) { throw invalid("Provide a base64 Ed25519 signature."); }
        if (signature.length != 64 || !Ed25519.verify(key.publicKey(), json.bytes(body.document()), signature)) {
            throw invalid("The advisor signature is invalid.");
        }
        UUID requestId = UUID.fromString(body.document().path("request_id").asText());
        if (credential.revokedAt() != null) {
            if (requestId.equals(credential.revocationId())) {
                return ResponseEntity.ok(response(credentialId, requestId, credential.revokedAt()));
            }
            throw conflict();
        }
        OffsetDateTime now = now();
        if (preparedAt.isAfter(now) || preparedAt.isBefore(now.minusMinutes(10))
                || preparedAt.isBefore(credential.issuedAt())) {
            throw invalid("The signed revocation request has expired.");
        }
        LedgerEventService.Event event;
        try { event = ledger.append(body.document(), signature, key.id()); }
        catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Fabric ledger is unavailable; the credential was not revoked.");
        }
        jdbc.update("UPDATE credentials SET revoked_at = ?, revocation_transaction_id = ? WHERE id = ?",
                event.committedAt(), requestId, credentialId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response(credentialId, requestId, event.committedAt()));
    }

    private Map<String, Object> document(AccessService.ClubAuthority advisor, Key key,
                                         Credential credential, String reason, OffsetDateTime preparedAt) {
        UUID requestId = UuidV5.url("instichain:credential-revocation:"
                + credential.id() + ":" + advisor.user().id() + ":" + preparedAt);
        return AdminController.action(requestId, "ACHIEVEMENT_REVOKED",
                Map.of("club_id", credential.clubId(), "credential_id", credential.id(),
                        "recipient_id", credential.recipientId()),
                Map.of("issuance_transaction_id", credential.issuanceId(), "reason", reason,
                        "prepared_at", preparedAt, "revoked_at", preparedAt),
                advisor.user().id(), key.id());
    }

    private Credential credential(UUID clubId, UUID credentialId, boolean lock) {
        String sql = """
                SELECT cr.id, c.club_id, cr.recipient_id, cr.issuance_transaction_id,
                       cr.issued_at, cr.revoked_at, cr.revocation_transaction_id
                FROM credentials cr JOIN competitions c ON c.id = cr.competition_id
                WHERE cr.id = ? AND c.club_id = ?
                """ + (lock ? " FOR UPDATE OF cr" : "");
        List<Credential> rows = jdbc.query(sql, (row, number) -> new Credential(
                row.getObject("id", UUID.class), row.getObject("club_id", UUID.class),
                row.getObject("recipient_id", UUID.class),
                row.getObject("issuance_transaction_id", UUID.class),
                row.getObject("issued_at", OffsetDateTime.class),
                row.getObject("revoked_at", OffsetDateTime.class),
                row.getObject("revocation_transaction_id", UUID.class)), credentialId, clubId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Credential not found.");
        return rows.getFirst();
    }

    private Key advisorKey(AccessService.ClubAuthority advisor) {
        // Look up the current key, not the appointment's: rotation must not lock advisors out.
        List<Key> keys = jdbc.query("""
                SELECT id, user_id, public_key FROM authority_keys
                WHERE user_id = ? AND purpose = 'advisor' AND revoked_at IS NULL
                ORDER BY created_at DESC, id LIMIT 1
                """, (row, number) -> new Key(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getBytes("public_key")), advisor.user().id());
        if (keys.isEmpty() || !advisor.user().id().equals(keys.getFirst().userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Advisor signing authority is inactive. Rotate or recover your signing key.");
        }
        return keys.getFirst();
    }

    private OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }

    private static Map<String, Object> response(UUID id, UUID requestId, OffsetDateTime time) {
        return Map.of("credential_id", id, "revocation_transaction_id", requestId, "revoked_at", time);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "This credential has already been revoked.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

package edu.iitm.instichain.domain;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.auth.InstituteEmail;
import edu.iitm.instichain.auth.UserAccount;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/advisor")
public class AdvisorController {
    private static final Set<String> ADVISOR = Set.of("faculty_advisor");
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final LedgerEventService ledger;
    private final CanonicalJson json;

    public AdvisorController(JdbcTemplate jdbc, AccessService access,
                             LedgerEventService ledger, CanonicalJson json) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
        this.json = json;
    }

    record RoleDraft(@NotBlank @Size(max = 254) String email,
                     @Pattern(regexp = "club_head|office_bearer") String position,
                     @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) {}
    record RevocationDraft(@NotBlank @Size(max = 500) String reason) {}
    record SignedDocument(@NotNull JsonNode document,
                          @NotBlank @Size(max = 88) String signature) {}
    record Key(UUID id, UUID userId, byte[] publicKey) {}

    @GetMapping("/access")
    Map<String, Boolean> advisorAccess(HttpServletRequest request) {
        UserAccount user = access.user(request);
        if (access.activeAppointments(user, ADVISOR, null).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "An active faculty advisor appointment is required.");
        }
        return Map.of("is_advisor", true);
    }

    @GetMapping("/clubs")
    List<Map<String, Object>> clubs(HttpServletRequest request) {
        UserAccount user = access.user(request);
        List<AccessService.Appointment> appointments = access.activeAppointments(user, ADVISOR, null);
        if (appointments.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "An active faculty advisor appointment is required.");
        }
        return appointments.stream().map(this::clubResponse).toList();
    }

    @GetMapping("/clubs/{clubId}/access")
    Map<String, Object> clubAccess(@PathVariable UUID clubId, HttpServletRequest request) {
        return clubResponse(access.club(request, clubId, ADVISOR).appointment());
    }

    @GetMapping("/clubs/{clubId}/roles")
    List<Map<String, Object>> roles(@PathVariable UUID clubId, HttpServletRequest request) {
        access.club(request, clubId, ADVISOR);
        return jdbc.query("""
                SELECT a.*, u.email, u.display_name, clock_timestamp() AS now
                FROM appointments a JOIN users u ON u.id = a.user_id
                WHERE a.club_id = ? AND a.position IN ('club_head', 'office_bearer')
                ORDER BY a.created_at DESC, a.id
                """, (result, row) -> {
            OffsetDateTime start = result.getObject("starts_at", OffsetDateTime.class);
            OffsetDateTime end = result.getObject("ends_at", OffsetDateTime.class);
            OffsetDateTime revoked = result.getObject("revoked_at", OffsetDateTime.class);
            OffsetDateTime now = result.getObject("now", OffsetDateTime.class);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", result.getObject("id", UUID.class));
            response.put("club_id", clubId);
            response.put("user_id", result.getObject("user_id", UUID.class));
            response.put("position", result.getString("position"));
            response.put("starts_at", start);
            response.put("ends_at", end);
            response.put("email", result.getString("email"));
            response.put("display_name", result.getString("display_name"));
            response.put("status", revoked != null ? "revoked" : !end.isAfter(now) ? "expired"
                    : start.isAfter(now) ? "scheduled" : "active");
            response.put("revoked_at", revoked);
            return response;
        }, clubId);
    }

    @PostMapping("/clubs/{clubId}/role-grants/prepare")
    Map<String, Object> prepareGrant(@PathVariable UUID clubId, @Valid @RequestBody RoleDraft body,
                                     HttpServletRequest request) {
        AccessService.ClubAuthority authority = access.club(request, clubId, ADVISOR);
        if (!body.endsAt().isAfter(body.startsAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "End time must be after start time");
        }
        String email = normalize(body.email());
        UserAccount recipient = userByEmail(email);
        if (recipient.id().equals(authority.user().id())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "An advisor cannot grant a club role to themselves.");
        }
        OffsetDateTime now = now();
        if (!body.endsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The appointment must end in the future.");
        }
        OffsetDateTime start = body.startsAt().isBefore(now) ? now : body.startsAt();
        Key key = advisorKey(authority);
        UUID appointmentId = UuidV5.url("instichain:appointment:" + clubId + ":" + recipient.id()
                + ":" + body.position() + ":" + start + ":" + body.endsAt());
        Map<String, Object> document = AdminController.action(appointmentId, "ROLE_GRANTED",
                Map.of("club_id", clubId, "appointment_id", appointmentId, "user_id", recipient.id()),
                Map.of("position", body.position(), "prepared_at", now,
                        "starts_at", start, "ends_at", body.endsAt()),
                authority.user().id(), key.id());
        return prepared(document);
    }

    @PostMapping("/clubs/{clubId}/role-grants")
    @Transactional
    org.springframework.http.ResponseEntity<Map<String, Object>> grant(
            @PathVariable UUID clubId, @Valid @RequestBody SignedDocument body,
            HttpServletRequest request) {
        AccessService.ClubAuthority authority = access.club(request, clubId, ADVISOR);
        Key key = advisorKey(authority);
        JsonNode document = body.document();
        requireText(document, "event_type", "ROLE_GRANTED");
        JsonNode objects = document.path("object_ids");
        JsonNode payload = document.path("payload");
        UUID requestId = uuid(document, "request_id");
        UUID appointmentId = uuid(objects, "appointment_id");
        UUID recipientId = uuid(objects, "user_id");
        String position = payload.path("position").asText();
        OffsetDateTime preparedAt = time(payload, "prepared_at");
        OffsetDateTime startsAt = time(payload, "starts_at");
        OffsetDateTime endsAt = time(payload, "ends_at");
        if (!uuid(objects, "club_id").equals(clubId) || !requestId.equals(appointmentId)
                || !uuid(document, "actor_id").equals(authority.user().id())
                || !uuid(document, "actor_key_id").equals(key.id())
                || !(position.equals("club_head") || position.equals("office_bearer"))) {
            throw invalid("The signed role grant does not match this advisor and club.");
        }
        UUID expectedId = UuidV5.url("instichain:appointment:" + clubId + ":" + recipientId
                + ":" + position + ":" + startsAt + ":" + endsAt);
        if (!expectedId.equals(requestId)) throw invalid("The signed role grant has an invalid appointment ID.");
        OffsetDateTime now = now();
        if (preparedAt.isAfter(now) || preparedAt.isBefore(now.minusMinutes(10))
                || startsAt.isBefore(preparedAt) || !endsAt.isAfter(now)) {
            throw invalid("The signed role grant has expired or contains an invalid term.");
        }
        UserAccount recipient = userById(recipientId);
        if (recipient.id().equals(authority.user().id())) {
            throw invalid("An advisor cannot grant a club role to themselves.");
        }
        byte[] signature = signature(body.signature(), key, document);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM appointments WHERE id = ?", requestId);
        if (!existing.isEmpty()) return org.springframework.http.ResponseEntity.ok(roleResponse(existing.getFirst()));
        String overlapSql = """
                SELECT EXISTS(SELECT 1 FROM appointments WHERE club_id = ? AND position = ?
                  AND revoked_at IS NULL AND starts_at < ? AND ends_at > ?
                """ + (position.equals("office_bearer") ? " AND user_id = ?" : "") + ")";
        Boolean overlap = position.equals("office_bearer")
                ? jdbc.queryForObject(overlapSql, Boolean.class, clubId, position, endsAt, startsAt, recipientId)
                : jdbc.queryForObject(overlapSql, Boolean.class, clubId, position, endsAt, startsAt);
        if (Boolean.TRUE.equals(overlap)) throw new ApiException(HttpStatus.CONFLICT,
                "This role overlaps an existing appointment.");
        append(document, signature, key.id(), "The Fabric ledger is unavailable; no role was granted.");
        jdbc.update("""
                INSERT INTO appointments(id, club_id, user_id, position, granted_by_id,
                    advisor_key_id, starts_at, ends_at, grant_transaction_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, clubId, recipientId, position, authority.user().id(), key.id(),
                startsAt, endsAt, requestId);
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", requestId, "club_id", clubId, "user_id", recipientId,
                "position", position, "starts_at", startsAt, "ends_at", endsAt));
    }

    @PostMapping("/clubs/{clubId}/roles/{appointmentId}/revocations/prepare")
    Map<String, Object> prepareRevocation(@PathVariable UUID clubId, @PathVariable UUID appointmentId,
                                          @Valid @RequestBody RevocationDraft body,
                                          HttpServletRequest request) {
        AccessService.ClubAuthority authority = access.club(request, clubId, ADVISOR);
        AppointmentRow appointment = roleAppointment(clubId, appointmentId);
        OffsetDateTime now = now();
        if (appointment.revokedAt() != null) throw new ApiException(HttpStatus.CONFLICT,
                "This club role has already been revoked.");
        if (!appointment.endsAt().isAfter(now)) throw new ApiException(HttpStatus.CONFLICT,
                "This club role has already expired.");
        Key key = advisorKey(authority);
        UUID requestId = UuidV5.url("instichain:role-revocation:" + appointmentId + ":" + now);
        Map<String, Object> document = AdminController.action(requestId, "ROLE_REVOKED",
                Map.of("club_id", clubId, "appointment_id", appointmentId,
                        "user_id", appointment.userId()),
                Map.of("position", appointment.position(),
                        "grant_transaction_id", appointment.grantTransactionId(),
                        "reason", body.reason().strip(), "prepared_at", now, "revoked_at", now),
                authority.user().id(), key.id());
        return prepared(document);
    }

    @PostMapping("/clubs/{clubId}/roles/{appointmentId}/revocations")
    @Transactional
    org.springframework.http.ResponseEntity<Map<String, Object>> revoke(
            @PathVariable UUID clubId, @PathVariable UUID appointmentId,
            @Valid @RequestBody SignedDocument body, HttpServletRequest request) {
        AccessService.ClubAuthority authority = access.club(request, clubId, ADVISOR);
        Key key = advisorKey(authority);
        JsonNode document = body.document();
        requireText(document, "event_type", "ROLE_REVOKED");
        JsonNode objects = document.path("object_ids");
        JsonNode payload = document.path("payload");
        UUID requestId = uuid(document, "request_id");
        OffsetDateTime preparedAt = time(payload, "prepared_at");
        OffsetDateTime revokedAt = time(payload, "revoked_at");
        if (!uuid(objects, "club_id").equals(clubId)
                || !uuid(objects, "appointment_id").equals(appointmentId)
                || !uuid(document, "actor_id").equals(authority.user().id())
                || !uuid(document, "actor_key_id").equals(key.id())
                || !preparedAt.equals(revokedAt)
                || !requestId.equals(UuidV5.url("instichain:role-revocation:" + appointmentId + ":" + revokedAt))) {
            throw invalid("The signed revocation does not match this advisor and club role.");
        }
        AppointmentRow appointment = roleAppointment(clubId, appointmentId);
        if (!uuid(objects, "user_id").equals(appointment.userId())
                || !payload.path("position").asText().equals(appointment.position())
                || !uuid(payload, "grant_transaction_id").equals(appointment.grantTransactionId())) {
            throw invalid("The signed revocation does not match the stored club role.");
        }
        byte[] signature = signature(body.signature(), key, document);
        if (appointment.revokedAt() != null) {
            if (requestId.equals(appointment.revocationTransactionId())
                    && revokedAt.equals(appointment.revokedAt())) {
                return org.springframework.http.ResponseEntity.ok(revocationResponse(appointmentId, requestId, revokedAt));
            }
            throw new ApiException(HttpStatus.CONFLICT, "This club role has already been revoked.");
        }
        OffsetDateTime now = now();
        if (preparedAt.isAfter(now) || preparedAt.isBefore(now.minusMinutes(10))
                || !appointment.endsAt().isAfter(now)) {
            throw invalid("The signed revocation has expired or the club role is inactive.");
        }
        append(document, signature, key.id(), "The Fabric ledger is unavailable; no role was revoked.");
        jdbc.update("UPDATE appointments SET revoked_at = ?, revocation_transaction_id = ? WHERE id = ?",
                revokedAt, requestId, appointmentId);
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(revocationResponse(appointmentId, requestId, revokedAt));
    }

    private Key advisorKey(AccessService.ClubAuthority authority) {
        UUID keyId = authority.appointment().advisorKeyId();
        if (keyId == null) throw new ApiException(HttpStatus.FORBIDDEN, "Advisor signing authority is inactive.");
        List<Key> keys = jdbc.query("""
                SELECT id, user_id, public_key FROM authority_keys
                WHERE id = ? AND purpose = 'advisor' AND revoked_at IS NULL
                """, (result, row) -> new Key(result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class), result.getBytes("public_key")), keyId);
        if (keys.isEmpty() || !keys.getFirst().userId().equals(authority.user().id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Advisor signing authority is inactive.");
        }
        return keys.getFirst();
    }

    private byte[] signature(String encoded, Key key, JsonNode document) {
        byte[] value;
        try { value = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw invalid("Provide a base64 Ed25519 signature"); }
        if (value.length != 64 || !Ed25519.verify(key.publicKey(), json.bytes(document), value)) {
            throw invalid("The advisor signature is invalid.");
        }
        return value;
    }

    private void append(JsonNode document, byte[] signature, UUID keyId, String unavailable) {
        try { ledger.append(document, signature, keyId); }
        catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, unavailable);
        }
    }

    private Map<String, Object> prepared(Map<String, Object> document) {
        return Map.of("document", document,
                "signing_payload", Base64.getEncoder().encodeToString(json.bytes(document)));
    }

    private Map<String, Object> clubResponse(AccessService.Appointment appointment) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", appointment.clubId()); response.put("slug", appointment.clubSlug());
        response.put("name", appointment.clubName()); response.put("description", appointment.clubDescription());
        response.put("appointment_id", appointment.id()); response.put("starts_at", appointment.startsAt());
        response.put("ends_at", appointment.endsAt());
        return response;
    }

    private UserAccount userByEmail(String email) {
        List<UserAccount> users = jdbc.query("""
                SELECT id, email, display_name, email_verified_at FROM users
                WHERE email = ? AND email_verified_at IS NOT NULL
                """, edu.iitm.instichain.auth.SessionService::mapUser, email);
        if (users.isEmpty()) throw invalid("The recipient must first verify their institute account.");
        return users.getFirst();
    }

    private UserAccount userById(UUID id) {
        List<UserAccount> users = jdbc.query("""
                SELECT id, email, display_name, email_verified_at FROM users
                WHERE id = ? AND email_verified_at IS NOT NULL
                """, edu.iitm.instichain.auth.SessionService::mapUser, id);
        if (users.isEmpty()) throw invalid("The recipient must have a verified institute account.");
        return users.getFirst();
    }

    private AppointmentRow roleAppointment(UUID clubId, UUID appointmentId) {
        List<AppointmentRow> rows = jdbc.query("""
                SELECT id, user_id, position, grant_transaction_id, ends_at, revoked_at,
                       revocation_transaction_id FROM appointments
                WHERE id = ? AND club_id = ? AND position IN ('club_head', 'office_bearer')
                """, (result, row) -> new AppointmentRow(result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class), result.getString("position"),
                result.getObject("grant_transaction_id", UUID.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("revoked_at", OffsetDateTime.class),
                result.getObject("revocation_transaction_id", UUID.class)), appointmentId, clubId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Club role appointment not found.");
        return rows.getFirst();
    }

    private Map<String, Object> roleResponse(Map<String, Object> row) {
        return Map.of("id", row.get("id"), "club_id", row.get("club_id"), "user_id", row.get("user_id"),
                "position", row.get("position"), "starts_at", row.get("starts_at"), "ends_at", row.get("ends_at"));
    }

    private Map<String, Object> revocationResponse(UUID appointment, UUID transaction, OffsetDateTime time) {
        return Map.of("appointment_id", appointment, "revocation_transaction_id", transaction, "revoked_at", time);
    }

    private OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    private static UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(node.path(field).asText()); }
        catch (IllegalArgumentException error) { throw invalid("The signed document contains an invalid identifier."); }
    }
    private static OffsetDateTime time(JsonNode node, String field) {
        try { return OffsetDateTime.parse(node.path(field).asText()); }
        catch (RuntimeException error) { throw invalid("The signed document contains an invalid timestamp."); }
    }
    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) throw invalid("The signed document has the wrong event type.");
    }
    private static String normalize(String value) {
        try { return InstituteEmail.normalize(value); }
        catch (IllegalArgumentException error) { throw invalid(error.getMessage()); }
    }
    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    private record AppointmentRow(UUID id, UUID userId, String position, UUID grantTransactionId,
                                  OffsetDateTime endsAt, OffsetDateTime revokedAt,
                                  UUID revocationTransactionId) {}
}

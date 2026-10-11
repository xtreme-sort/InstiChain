package edu.iitm.instichain.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.iitm.instichain.auth.InstituteEmail;
import edu.iitm.instichain.common.ApiException;
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
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final LedgerEventService ledger;

    public AdminController(JdbcTemplate jdbc, AccessService access, LedgerEventService ledger) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
    }

    record ClubRequest(@NotBlank @Size(max = 100)
                       @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
                       @NotBlank @Size(max = 200) String name,
                       @Size(max = 2000) String description) {}
    record AdvisorRequest(@NotBlank @Size(max = 254) String email,
                          @JsonProperty("verification_reference")
                          @NotBlank @Size(max = 500) String verificationReference,
                          @JsonProperty("starts_at") @NotNull OffsetDateTime startsAt,
                          @JsonProperty("ends_at") @NotNull OffsetDateTime endsAt) {}

    @GetMapping("/access")
    Map<String, Boolean> access(HttpServletRequest request) {
        access.admin(request);
        return Map.of("is_admin", true);
    }

    @GetMapping("/clubs")
    List<Map<String, Object>> clubs(HttpServletRequest request) {
        access.admin(request);
        List<Map<String, Object>> clubs = jdbc.query("""
                SELECT id, slug, name, description FROM clubs ORDER BY name
                """, (result, row) -> {
            Map<String, Object> club = new LinkedHashMap<>();
            club.put("id", result.getObject("id", UUID.class));
            club.put("slug", result.getString("slug"));
            club.put("name", result.getString("name"));
            club.put("description", result.getString("description"));
            club.put("advisors", new java.util.ArrayList<>());
            return club;
        });
        Map<UUID, Map<String, Object>> byId = new java.util.HashMap<>();
        clubs.forEach(club -> byId.put((UUID) club.get("id"), club));
        jdbc.query("""
                SELECT a.id, a.club_id, a.starts_at, a.ends_at, a.revoked_at,
                       u.email, u.display_name, clock_timestamp() AS now
                FROM appointments a JOIN users u ON u.id = a.user_id
                WHERE a.position = 'faculty_advisor' ORDER BY a.starts_at
                """, result -> {
            Map<String, Object> club = byId.get(result.getObject("club_id", UUID.class));
            if (club != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> advisors = (List<Map<String, Object>>) club.get("advisors");
                OffsetDateTime now = result.getObject("now", OffsetDateTime.class);
                OffsetDateTime start = result.getObject("starts_at", OffsetDateTime.class);
                OffsetDateTime end = result.getObject("ends_at", OffsetDateTime.class);
                OffsetDateTime revoked = result.getObject("revoked_at", OffsetDateTime.class);
                advisors.add(Map.ofEntries(
                        Map.entry("id", result.getObject("id", UUID.class)),
                        Map.entry("email", result.getString("email")),
                        Map.entry("name", result.getString("display_name")),
                        Map.entry("starts_at", start), Map.entry("ends_at", end),
                        Map.entry("status", revoked != null ? "revoked"
                                : !end.isAfter(now) ? "expired" : start.isAfter(now) ? "scheduled" : "active")));
            }
        });
        return clubs;
    }

    @PostMapping("/clubs")
    @Transactional
    org.springframework.http.ResponseEntity<Map<String, Object>> createClub(
            @Valid @RequestBody ClubRequest body, HttpServletRequest request) {
        AccessService.Administrator admin = access.admin(request);
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM clubs WHERE slug = ?)",
                Boolean.class, body.slug());
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.CONFLICT, "A club with this slug already exists.");
        }
        UUID clubId = UuidV5.url("instichain:club:" + body.slug());
        UUID requestId = clubId;
        Map<String, Object> document = action(requestId, "CLUB_CREATED",
                Map.of("club_id", clubId), nullableMap(
                        "slug", body.slug(), "name", body.name().strip(),
                        "description", blankToNull(body.description())), admin.user().id(), admin.keyId());
        try {
            ledger.appendAdmin(document, admin.keyId(), admin.publicKey());
        } catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Fabric ledger is unavailable; no club was created.");
        }
        jdbc.update("""
                INSERT INTO clubs(id, slug, name, description, created_by_id)
                VALUES (?, ?, ?, ?, ?)
                """, clubId, body.slug(), body.name().strip(), blankToNull(body.description()), admin.user().id());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", clubId, "name", body.name().strip(), "slug", body.slug()));
    }

    @PostMapping("/clubs/{clubId}/advisors")
    @Transactional
    org.springframework.http.ResponseEntity<Map<String, Object>> appointAdvisor(
            @PathVariable UUID clubId, @Valid @RequestBody AdvisorRequest body,
            HttpServletRequest request) {
        AccessService.Administrator admin = access.admin(request);
        Boolean clubExists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM clubs WHERE id = ?)",
                Boolean.class, clubId);
        if (!Boolean.TRUE.equals(clubExists)) throw new ApiException(HttpStatus.NOT_FOUND, "Club not found.");
        if (!body.endsAt().isAfter(body.startsAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "End time must be after start time");
        }
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (!body.endsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The appointment must end in the future.");
        }
        String email;
        try {
            email = InstituteEmail.normalize(body.email());
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
        List<Map<String, Object>> users = jdbc.queryForList("""
                SELECT u.id, u.email_verified_at, k.public_key
                FROM users u LEFT JOIN user_signing_keys k
                  ON k.user_id = u.id AND k.revoked_at IS NULL
                WHERE u.email = ?
                """, email);
        if (users.isEmpty() || users.getFirst().get("email_verified_at") == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The advisor must first verify their institute account.");
        }
        UUID userId = (UUID) users.getFirst().get("id");
        byte[] rawKey = (byte[]) users.getFirst().get("public_key");
        if (rawKey == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The advisor must sign in once to create their browser signing key.");
        }
        OffsetDateTime start = body.startsAt().isBefore(now) ? now : body.startsAt();
        Boolean overlap = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM appointments
                 WHERE club_id = ? AND position = 'faculty_advisor' AND revoked_at IS NULL
                   AND starts_at < ? AND ends_at > ?)
                """, Boolean.class, clubId, body.endsAt(), start);
        if (Boolean.TRUE.equals(overlap)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This club already has an advisor during the requested term.");
        }
        UUID keyId = UuidV5.url("instichain:advisor-key:" + Base64.getEncoder().encodeToString(rawKey));
        List<Map<String, Object>> keys = jdbc.queryForList("""
                SELECT id, user_id, purpose, revoked_at FROM authority_keys WHERE public_key = ?
                """, rawKey);
        if (!keys.isEmpty()) {
            Map<String, Object> key = keys.getFirst();
            if (!userId.equals(key.get("user_id")) || !"advisor".equals(key.get("purpose"))
                    || key.get("revoked_at") != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "This public key cannot be assigned to this advisor.");
            }
            keyId = (UUID) key.get("id");
        } else {
            jdbc.update("""
                    INSERT INTO authority_keys(id, user_id, public_key, purpose, verified_by_id, verification_reference)
                    VALUES (?, ?, ?, 'advisor', ?, ?)
                    """, keyId, userId, rawKey, admin.user().id(), body.verificationReference().strip());
        }
        UUID appointmentId = UuidV5.url("instichain:appointment:" + clubId + ":" + userId
                + ":" + body.startsAt() + ":" + body.endsAt());
        Map<String, Object> document = action(appointmentId, "ADVISOR_APPOINTED",
                Map.of("club_id", clubId, "user_id", userId, "appointment_id", appointmentId),
                Map.of("position", "faculty_advisor", "key_id", keyId,
                        "public_key", Base64.getEncoder().encodeToString(rawKey),
                        "starts_at", start, "ends_at", body.endsAt()),
                admin.user().id(), admin.keyId());
        try {
            ledger.appendAdmin(document, admin.keyId(), admin.publicKey());
        } catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Fabric ledger is unavailable; no advisor was appointed.");
        }
        try {
            jdbc.update("""
                    INSERT INTO appointments(id, club_id, user_id, position, granted_by_id,
                        advisor_key_id, verification_reference, starts_at, ends_at, grant_transaction_id)
                    VALUES (?, ?, ?, 'faculty_advisor', ?, ?, ?, ?, ?, ?)
                    """, appointmentId, clubId, userId, admin.user().id(), keyId,
                    body.verificationReference().strip(), start, body.endsAt(), appointmentId);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "This advisor appointment already exists.");
        }
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", appointmentId, "club_id", clubId, "advisor_id", userId,
                "key_id", keyId, "starts_at", start, "ends_at", body.endsAt()));
    }

    public static Map<String, Object> action(UUID requestId, String eventType,
                                             Map<String, Object> objectIds,
                                             Map<String, Object> payload,
                                             UUID actorId, UUID actorKeyId) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("request_id", requestId);
        action.put("event_type", eventType);
        action.put("object_ids", objectIds);
        action.put("payload", payload);
        action.put("actor_id", actorId);
        action.put("actor_key_id", actorKeyId);
        return action;
    }

    private static Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

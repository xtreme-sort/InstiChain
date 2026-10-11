package edu.iitm.instichain.auth;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import edu.iitm.instichain.config.InstiChainProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/email")
public class EmailAuthController {
    private static final String REQUEST_MESSAGE =
            "If this address needs verification, a link will be sent. Check your inbox.";
    private static final String INVALID_MESSAGE =
            "This verification link is invalid or expired. Request a new one.";
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final MailService mail;
    private final InstiChainProperties properties;
    private final SecureRandom random = new SecureRandom();

    public EmailAuthController(JdbcTemplate jdbc, SessionService sessions, MailService mail,
                               InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.mail = mail;
        this.properties = properties;
    }

    record EmailRequest(@NotBlank @Size(max = 254) String email) {}
    record TokenRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String token) {}
    record ConfirmRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String token,
                          @Size(max = 200) String displayName) {}

    @PostMapping("/request")
    @Transactional
    ResponseEntity<Map<String, String>> request(@Valid @RequestBody EmailRequest body) {
        final String email;
        try {
            email = InstituteEmail.normalize(body.email());
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
        lockEmail(email);
        OffsetDateTime now = now();
        List<OffsetDateTime> recent = jdbc.queryForList("""
                SELECT created_at FROM email_verifications
                WHERE email = ? AND created_at > clock_timestamp() - interval '1 day'
                ORDER BY created_at DESC
                """, OffsetDateTime.class, email);
        if (recent.size() >= 5 || (!recent.isEmpty() && recent.getFirst().isAfter(now.minusMinutes(1)))) {
            return accepted();
        }
        Boolean verified = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users WHERE email = ? AND email_verified_at IS NOT NULL)
                """, Boolean.class, email);
        String token = randomToken();
        jdbc.update("""
                UPDATE email_verifications SET invalidated_at = ?
                WHERE email = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, now, email);
        jdbc.update("""
                INSERT INTO email_verifications(id, email, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), email, Hashing.sha256(token), now,
                now.plusMinutes(properties.verificationTtlMinutes()));
        try {
            mail.sendVerification(email, token, Boolean.TRUE.equals(verified));
        } catch (MailException | IllegalStateException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is unavailable. Please try again later.");
        }
        return accepted();
    }

    @PostMapping("/inspect")
    ResponseEntity<Map<String, Boolean>> inspect(@Valid @RequestBody TokenRequest body) {
        Verification verification = findValid(body.token(), false);
        Boolean verified = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users WHERE email = ? AND email_verified_at IS NOT NULL)
                """, Boolean.class, verification.email());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("requires_name", !Boolean.TRUE.equals(verified)));
    }

    @PostMapping("/confirm")
    @Transactional
    ResponseEntity<Map<String, String>> confirm(@Valid @RequestBody ConfirmRequest body,
                                                 HttpServletResponse servletResponse) {
        String digest = Hashing.sha256(body.token());
        String email;
        try {
            email = jdbc.queryForObject(
                    "SELECT email FROM email_verifications WHERE token_hash = ?", String.class, digest);
        } catch (EmptyResultDataAccessException missing) {
            throw invalidLink();
        }
        lockEmail(email);
        Verification verification = findValid(body.token(), true);
        List<UserAccount> existing = jdbc.query("""
                SELECT id, email, display_name, email_verified_at FROM users WHERE email = ? FOR UPDATE
                """, SessionService::mapUser, email);
        boolean requiresName = existing.isEmpty() || existing.getFirst().emailVerifiedAt() == null;
        String name = body.displayName() == null ? null : body.displayName().strip();
        if (requiresName && (name == null || name.isEmpty())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Your full name is required to finish registration.");
        }
        OffsetDateTime now = now();
        UUID userId;
        if (existing.isEmpty()) {
            userId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO users(id, email, display_name, email_verified_at)
                    VALUES (?, ?, ?, ?)
                    """, userId, email, name, now);
        } else {
            userId = existing.getFirst().id();
            if (existing.getFirst().emailVerifiedAt() == null) {
                jdbc.update("UPDATE users SET display_name = ?, email_verified_at = ? WHERE id = ?",
                        name, now, userId);
            }
        }
        jdbc.update("UPDATE email_verifications SET consumed_at = ? WHERE id = ?", now, verification.id());
        jdbc.update("""
                UPDATE email_verifications SET invalidated_at = ?
                WHERE email = ? AND id <> ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, now, email, verification.id());
        String sessionToken = sessions.create(userId);
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, sessionToken)
                .httpOnly(true).secure(sessions.secureCookie()).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(properties.sessionTtlDays())).build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", requiresName
                        ? "Your institute email is verified." : "You are signed in."));
    }

    private Verification findValid(String token, boolean lock) {
        String sql = """
                SELECT id, email, expires_at, consumed_at, invalidated_at
                FROM email_verifications WHERE token_hash = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<Verification> records = jdbc.query(sql, (result, row) -> new Verification(
                result.getObject("id", UUID.class), result.getString("email"),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getObject("consumed_at", OffsetDateTime.class),
                result.getObject("invalidated_at", OffsetDateTime.class)), Hashing.sha256(token));
        OffsetDateTime now = now();
        if (records.isEmpty() || records.getFirst().consumedAt() != null
                || records.getFirst().invalidatedAt() != null
                || !records.getFirst().expiresAt().isAfter(now)) {
            throw invalidLink();
        }
        return records.getFirst();
    }

    private void lockEmail(String email) {
        byte[] hash = Hashing.sha256Bytes(email);
        long key = ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();
        jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> { }, key);
    }

    private OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseEntity<Map<String, String>> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", REQUEST_MESSAGE));
    }

    private ApiException invalidLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_MESSAGE);
    }

    private record Verification(UUID id, String email, OffsetDateTime expiresAt,
                                OffsetDateTime consumedAt, OffsetDateTime invalidatedAt) {}
}

package edu.iitm.instichain.auth;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import edu.iitm.instichain.config.InstiChainProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    public static final String COOKIE_NAME = "instichain_session";
    private static final String UNAUTHORIZED = "Sign in to continue.";
    private final JdbcTemplate jdbc;
    private final InstiChainProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SessionService(JdbcTemplate jdbc, InstiChainProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public UserAccount requireUser(HttpServletRequest request) {
        String token = cookie(request);
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
        }
        List<UserAccount> users = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.email_verified_at
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                """, SessionService::mapUser, Hashing.sha256(token));
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
        }
        return users.getFirst();
    }

    public String create(UUID userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO sessions(id, user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 day'))
                """, UUID.randomUUID(), userId, Hashing.sha256(token), properties.sessionTtlDays());
        return token;
    }

    public void revoke(String token) {
        if (token != null) {
            jdbc.update("""
                    UPDATE sessions SET revoked_at = clock_timestamp()
                    WHERE token_hash = ? AND revoked_at IS NULL
                    """, Hashing.sha256(token));
        }
    }

    public String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public boolean secureCookie() {
        return properties.publicAppUrl().startsWith("https://");
    }

    public void requireSameOrigin(HttpServletRequest request) {
        URI app = URI.create(properties.publicAppUrl());
        String expected = app.getScheme() + "://" + app.getAuthority();
        if (!expected.equals(request.getHeader("Origin"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "A same-origin request is required.");
        }
    }

    public static UserAccount mapUser(ResultSet result, int row) throws SQLException {
        return new UserAccount(
                result.getObject("id", UUID.class),
                result.getString("email"),
                result.getString("display_name"),
                result.getObject("email_verified_at", OffsetDateTime.class));
    }
}

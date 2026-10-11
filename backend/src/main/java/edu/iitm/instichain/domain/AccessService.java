package edu.iitm.instichain.domain;

import edu.iitm.instichain.auth.SessionService;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.fabric.LedgerEventService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final InstiChainProperties properties;
    private final LedgerEventService ledger;

    public AccessService(JdbcTemplate jdbc, SessionService sessions, InstiChainProperties properties,
                         LedgerEventService ledger) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.properties = properties;
        this.ledger = ledger;
    }

    public UserAccount user(HttpServletRequest request) {
        return sessions.requireUser(request);
    }

    public Administrator admin(HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        mutationGuard(request);
        List<Administrator> rows = jdbc.query("""
                SELECT a.user_id, a.key_id, k.public_key
                FROM administrator a JOIN authority_keys k ON k.id = a.key_id
                WHERE a.user_id = ? AND a.revoked_at IS NULL AND k.revoked_at IS NULL
                """, (result, row) -> new Administrator(user,
                result.getObject("key_id", UUID.class), result.getBytes("public_key")), user.id());
        if (rows.isEmpty() || user.emailVerifiedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Institute administrator access is required.");
        }
        return rows.getFirst();
    }

    public List<Appointment> activeAppointments(UserAccount user, Set<String> positions, UUID clubId) {
        String placeholders = String.join(",", positions.stream().map(value -> "?").toList());
        String sql = """
                SELECT a.*, c.slug, c.name AS club_name, c.description
                FROM appointments a JOIN clubs c ON c.id = a.club_id
                WHERE a.user_id = ? AND a.position IN (%s) AND a.revoked_at IS NULL
                  AND a.starts_at <= clock_timestamp() AND a.ends_at > clock_timestamp()
                """.formatted(placeholders) + (clubId == null ? "" : " AND a.club_id = ?")
                + " ORDER BY a.starts_at, a.id";
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(user.id());
        arguments.addAll(positions);
        if (clubId != null) arguments.add(clubId);
        return jdbc.query(sql, AccessService::mapAppointment, arguments.toArray());
    }

    public ClubAuthority club(HttpServletRequest request, UUID clubId, Set<String> positions) {
        UserAccount user = sessions.requireUser(request);
        mutationGuard(request);
        List<Appointment> appointments = activeAppointments(user, positions, clubId);
        if (appointments.isEmpty() || user.emailVerifiedAt() == null) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM clubs WHERE id = ?)",
                    Boolean.class, clubId);
            if (!Boolean.TRUE.equals(exists)) throw new ApiException(HttpStatus.NOT_FOUND, "Club not found.");
            throw new ApiException(HttpStatus.FORBIDDEN, "An active role for this club is required.");
        }
        return new ClubAuthority(user, appointments.getFirst());
    }

    public void mutationGuard(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            URI app = URI.create(properties.publicAppUrl());
            String expected = app.getScheme() + "://" + app.getAuthority();
            if (!expected.equals(request.getHeader("Origin"))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "A same-origin request is required.");
            }
            ledger.lock();
        }
    }

    static Appointment mapAppointment(ResultSet result, int row) throws SQLException {
        return new Appointment(
                result.getObject("id", UUID.class), result.getObject("club_id", UUID.class),
                result.getObject("user_id", UUID.class), result.getString("position"),
                result.getObject("advisor_key_id", UUID.class),
                result.getObject("grant_transaction_id", UUID.class),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("revoked_at", OffsetDateTime.class),
                result.getObject("revocation_transaction_id", UUID.class),
                result.getString("slug"), result.getString("club_name"), result.getString("description"));
    }

    public record Administrator(UserAccount user, UUID keyId, byte[] publicKey) {}
    public record Appointment(UUID id, UUID clubId, UUID userId, String position, UUID advisorKeyId,
                              UUID grantTransactionId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                              OffsetDateTime revokedAt, UUID revocationTransactionId,
                              String clubSlug, String clubName, String clubDescription) {}
    public record ClubAuthority(UserAccount user, Appointment appointment) {}
}

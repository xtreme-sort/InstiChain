package edu.iitm.instichain.sharing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only the public fields a verifier may see for a share link. */
@Repository
public class PublicShareRepository {
    private final JdbcTemplate jdbc;

    public PublicShareRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ActiveShare(UUID id, String recipientName, OffsetDateTime expiresAt) {}
    public record SharedCredential(UUID id, String award, String competition, String club,
                                   String issuer, OffsetDateTime issuedAt,
                                   String fabricTransactionId) {}

    /** Unknown, expired and revoked links all return empty, so callers cannot tell them apart. */
    public Optional<ActiveShare> activeShare(String tokenHash) {
        return jdbc.query("""
                SELECT s.id, u.display_name, s.expires_at
                FROM share_links s JOIN users u ON u.id = s.owner_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL
                  AND s.expires_at > clock_timestamp()
                """, (row, number) -> new ActiveShare(row.getObject("id", UUID.class),
                row.getString("display_name"), row.getObject("expires_at", OffsetDateTime.class)),
                tokenHash).stream().findFirst();
    }

    public List<SharedCredential> credentials(UUID shareId) {
        return jdbc.query("""
                SELECT cr.id, cr.award, c.title, cl.name AS club, adv.display_name AS issuer,
                       cr.issued_at, ft.fabric_transaction_id
                FROM share_link_credentials sc
                JOIN credentials cr ON cr.id = sc.credential_id AND cr.recipient_id = sc.owner_id
                JOIN competitions c ON c.id = cr.competition_id
                JOIN clubs cl ON cl.id = c.club_id
                JOIN users adv ON adv.id = cr.advisor_id
                LEFT JOIN fabric_transactions ft ON ft.request_id = cr.issuance_transaction_id
                WHERE sc.share_id = ?
                ORDER BY cr.issued_at, cr.id
                """, (row, number) -> new SharedCredential(row.getObject("id", UUID.class),
                row.getString("award"), row.getString("title"), row.getString("club"),
                row.getString("issuer"), row.getObject("issued_at", OffsetDateTime.class),
                row.getString("fabric_transaction_id")), shareId);
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }
}

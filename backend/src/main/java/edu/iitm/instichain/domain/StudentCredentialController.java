package edu.iitm.instichain.domain;

import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credentials")
public class StudentCredentialController {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final CredentialStatus status;

    public StudentCredentialController(JdbcTemplate jdbc, AccessService access, CredentialStatus status) {
        this.jdbc = jdbc;
        this.access = access;
        this.status = status;
    }

    record Credential(UUID id, UUID clubId, String clubName, UUID competitionId,
                      String competitionTitle, UUID resultId, int submissionVersion,
                      String award, String proofDigest, UUID advisorId, String advisorName,
                      UUID issuanceTransactionId, String fabricTransactionId,
                      OffsetDateTime issuedAt, UUID supersedesId,
                      OffsetDateTime revokedAt, UUID revocationTransactionId,
                      String lifecycleStatus, String proofAvailability) {}

    @GetMapping
    List<Credential> list(HttpServletRequest request) {
        UserAccount student = access.user(request);
        return jdbc.query(select() + " WHERE cr.recipient_id = ? ORDER BY cr.issued_at DESC, cr.id",
                this::map, student.id());
    }

    @GetMapping("/{credentialId}")
    Credential read(@PathVariable UUID credentialId, HttpServletRequest request) {
        UserAccount student = access.user(request);
        List<Credential> rows = jdbc.query(select() + " WHERE cr.id = ? AND cr.recipient_id = ?",
                this::map, credentialId, student.id());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Credential not found.");
        return rows.getFirst();
    }

    private String select() {
        return """
                SELECT cr.*, c.club_id, c.title AS competition_title, b.name AS club_name,
                       a.display_name AS advisor_name, tx.fabric_transaction_id,
                       EXISTS(SELECT 1 FROM credentials next WHERE next.supersedes_id = cr.id)
                         OR EXISTS(SELECT 1 FROM fabric_transactions successor
                           WHERE successor.event_type = 'ACHIEVEMENT_ISSUED'
                             AND successor.payload->'superseded_credential_ids'
                               @> jsonb_build_array(cr.id::text)) AS superseded
                FROM credentials cr
                JOIN competitions c ON c.id = cr.competition_id
                JOIN clubs b ON b.id = c.club_id
                JOIN users a ON a.id = cr.advisor_id
                JOIN fabric_transactions tx ON tx.request_id = cr.issuance_transaction_id
                """;
    }

    private Credential map(ResultSet row, int number) throws SQLException {
        OffsetDateTime revokedAt = row.getObject("revoked_at", OffsetDateTime.class);
        return new Credential(row.getObject("id", UUID.class), row.getObject("club_id", UUID.class),
                row.getString("club_name"), row.getObject("competition_id", UUID.class),
                row.getString("competition_title"), row.getObject("result_id", UUID.class),
                row.getInt("submission_version"), row.getString("award"), row.getString("proof_digest"),
                row.getObject("advisor_id", UUID.class), row.getString("advisor_name"),
                row.getObject("issuance_transaction_id", UUID.class), row.getString("fabric_transaction_id"),
                row.getObject("issued_at", OffsetDateTime.class), row.getObject("supersedes_id", UUID.class),
                revokedAt, row.getObject("revocation_transaction_id", UUID.class),
                status.current(revokedAt, row.getBoolean("superseded")), "digest_only");
    }
}

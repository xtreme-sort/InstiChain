package edu.iitm.instichain.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.iitm.instichain.auth.InstituteEmail;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.UuidV5;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
@RequestMapping("/api/clubs/{clubId}/competitions/{competitionId}/results")
public class ResultController {
    private static final Set<String> VIEWERS = Set.of("faculty_advisor", "club_head", "office_bearer");
    private static final Set<String> OFFICERS = Set.of("club_head", "office_bearer");
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public ResultController(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    record SubmissionRequest(@NotBlank @Size(max = 250) String award,
                             @JsonProperty("proof_digest")
                             @Pattern(regexp = "^[0-9a-f]{64}$") String proofDigest,
                             @JsonProperty("recipient_emails")
                             @NotEmpty @Size(max = 100) List<@NotBlank String> recipientEmails) {}
    record Recipient(UUID id, String email, String displayName) {}
    record Submission(UUID id, UUID resultId, UUID competitionId, int version, String award,
                      String proofDigest, UUID submittedById, String submittedByName,
                      OffsetDateTime submittedAt, List<Recipient> recipients) {}

    @GetMapping
    List<Submission> list(@PathVariable UUID clubId, @PathVariable UUID competitionId,
                          HttpServletRequest request) {
        access.club(request, clubId, VIEWERS);
        requireCompetition(clubId, competitionId);
        List<UUID> ids = jdbc.queryForList("""
                SELECT s.id FROM result_submissions s
                JOIN competition_results r ON r.id = s.result_id
                WHERE r.competition_id = ? ORDER BY s.submitted_at, s.id
                """, UUID.class, competitionId);
        return ids.stream().map(this::submission).toList();
    }

    @GetMapping("/{resultId}/versions/{version}")
    Submission read(@PathVariable UUID clubId, @PathVariable UUID competitionId,
                    @PathVariable UUID resultId, @PathVariable int version,
                    HttpServletRequest request) {
        access.club(request, clubId, VIEWERS);
        requireCompetition(clubId, competitionId);
        List<UUID> ids = jdbc.queryForList("""
                SELECT s.id FROM result_submissions s
                JOIN competition_results r ON r.id = s.result_id
                WHERE r.id = ? AND r.competition_id = ? AND s.version = ?
                """, UUID.class, resultId, competitionId, version);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Result submission not found.");
        return submission(ids.getFirst());
    }

    @PostMapping
    @Transactional
    org.springframework.http.ResponseEntity<Submission> create(
            @PathVariable UUID clubId, @PathVariable UUID competitionId,
            @Valid @RequestBody SubmissionRequest body, HttpServletRequest request) {
        AccessService.ClubAuthority officer = access.club(request, clubId, OFFICERS);
        requireCompetition(clubId, competitionId);
        UUID resultId = UUID.randomUUID();
        jdbc.update("INSERT INTO competition_results(id, competition_id, created_by_id) VALUES (?, ?, ?)",
                resultId, competitionId, officer.user().id());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(save(resultId, 1, body, officer.user()));
    }

    @PostMapping("/{resultId}/versions")
    @Transactional
    org.springframework.http.ResponseEntity<Submission> version(
            @PathVariable UUID clubId, @PathVariable UUID competitionId, @PathVariable UUID resultId,
            @Valid @RequestBody SubmissionRequest body, HttpServletRequest request) {
        AccessService.ClubAuthority officer = access.club(request, clubId, OFFICERS);
        requireCompetition(clubId, competitionId);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM competition_results WHERE id = ? AND competition_id = ?)
                """, Boolean.class, resultId, competitionId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Competition result not found.");
        }
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(max(version), 0) + 1 FROM result_submissions WHERE result_id = ?",
                Integer.class, resultId);
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(save(resultId, version, body, officer.user()));
    }

    private Submission save(UUID resultId, int version, SubmissionRequest body, UserAccount submitter) {
        List<String> emails = body.recipientEmails().stream().map(value -> {
            try { return InstituteEmail.normalize(value); }
            catch (IllegalArgumentException error) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
            }
        }).toList();
        if (new LinkedHashSet<>(emails).size() != emails.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Recipient emails must be unique");
        }
        List<Recipient> recipients = new ArrayList<>();
        for (String email : emails) {
            List<Recipient> found = jdbc.query("""
                    SELECT id, email, display_name FROM users
                    WHERE email = ? AND email_verified_at IS NOT NULL
                    """, (result, row) -> new Recipient(result.getObject("id", UUID.class),
                    result.getString("email"), result.getString("display_name")), email);
            if (found.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Every recipient must have a verified institute account.");
            }
            recipients.add(found.getFirst());
        }
        UUID submissionId = UuidV5.url("instichain:result-submission:" + resultId + ":" + version);
        jdbc.update("""
                INSERT INTO result_submissions(id, result_id, version, award, proof_digest, submitted_by_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, submissionId, resultId, version, body.award().strip(),
                body.proofDigest(), submitter.id());
        recipients.forEach(recipient -> jdbc.update(
                "INSERT INTO result_recipients(submission_id, recipient_id) VALUES (?, ?)",
                submissionId, recipient.id()));
        return submission(submissionId);
    }

    private Submission submission(UUID id) {
        Submission base = jdbc.query("""
                SELECT s.*, r.competition_id, u.display_name AS submitted_by_name
                FROM result_submissions s
                JOIN competition_results r ON r.id = s.result_id
                JOIN users u ON u.id = s.submitted_by_id WHERE s.id = ?
                """, (result, row) -> new Submission(result.getObject("id", UUID.class),
                result.getObject("result_id", UUID.class), result.getObject("competition_id", UUID.class),
                result.getInt("version"), result.getString("award"), result.getString("proof_digest"),
                result.getObject("submitted_by_id", UUID.class), result.getString("submitted_by_name"),
                result.getObject("submitted_at", OffsetDateTime.class), List.of()), id).getFirst();
        List<Recipient> recipients = jdbc.query("""
                SELECT u.id, u.email, u.display_name FROM users u
                JOIN result_recipients rr ON rr.recipient_id = u.id
                WHERE rr.submission_id = ? ORDER BY u.email
                """, (result, row) -> new Recipient(result.getObject("id", UUID.class),
                result.getString("email"), result.getString("display_name")), id);
        return new Submission(base.id(), base.resultId(), base.competitionId(), base.version(),
                base.award(), base.proofDigest(), base.submittedById(), base.submittedByName(),
                base.submittedAt(), recipients);
    }

    private void requireCompetition(UUID clubId, UUID competitionId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM competitions WHERE id = ? AND club_id = ?)
                """, Boolean.class, competitionId, clubId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException(HttpStatus.NOT_FOUND, "Competition not found.");
    }
}

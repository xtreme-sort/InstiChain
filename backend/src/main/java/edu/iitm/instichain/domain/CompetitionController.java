package edu.iitm.instichain.domain;

import edu.iitm.instichain.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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
@RequestMapping("/api/clubs/{clubId}/competitions")
public class CompetitionController {
    private static final Set<String> VIEWERS = Set.of("faculty_advisor", "club_head", "office_bearer");
    private static final Set<String> OFFICERS = Set.of("club_head", "office_bearer");
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public CompetitionController(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    record CompetitionRequest(@NotBlank @Size(max = 250) String title,
                              @Size(max = 5000) String description,
                              @NotNull OffsetDateTime startsAt,
                              OffsetDateTime endsAt) {}
    record CompetitionResponse(UUID id, UUID clubId, String title, String description,
                               OffsetDateTime startsAt, OffsetDateTime endsAt,
                               UUID createdById, String createdByName, OffsetDateTime createdAt) {}

    @GetMapping
    List<CompetitionResponse> list(@PathVariable UUID clubId, HttpServletRequest request) {
        access.club(request, clubId, VIEWERS);
        return jdbc.query(selectSql() + " WHERE c.club_id = ? ORDER BY c.starts_at DESC, c.id",
                CompetitionController::map, clubId);
    }

    @GetMapping("/{competitionId}")
    CompetitionResponse read(@PathVariable UUID clubId, @PathVariable UUID competitionId,
                             HttpServletRequest request) {
        access.club(request, clubId, VIEWERS);
        List<CompetitionResponse> rows = jdbc.query(selectSql() + " WHERE c.id = ? AND c.club_id = ?",
                CompetitionController::map, competitionId, clubId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Competition not found.");
        return rows.getFirst();
    }

    @PostMapping
    @Transactional
    org.springframework.http.ResponseEntity<CompetitionResponse> create(
            @PathVariable UUID clubId, @Valid @RequestBody CompetitionRequest body,
            HttpServletRequest request) {
        AccessService.ClubAuthority officer = access.club(request, clubId, OFFICERS);
        if (body.endsAt() != null && !body.endsAt().isAfter(body.startsAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "End time must be after start time");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO competitions(id, club_id, title, description, starts_at, ends_at, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, clubId, body.title().strip(), blankToNull(body.description()),
                body.startsAt(), body.endsAt(), officer.user().id());
        CompetitionResponse response = jdbc.query(selectSql() + " WHERE c.id = ?",
                CompetitionController::map, id).getFirst();
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static String selectSql() {
        return """
                SELECT c.*, u.display_name AS created_by_name
                FROM competitions c JOIN users u ON u.id = c.created_by_id
                """;
    }

    private static CompetitionResponse map(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new CompetitionResponse(result.getObject("id", UUID.class),
                result.getObject("club_id", UUID.class), result.getString("title"),
                result.getString("description"), result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("created_by_id", UUID.class), result.getString("created_by_name"),
                result.getObject("created_at", OffsetDateTime.class));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

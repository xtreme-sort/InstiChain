package edu.iitm.instichain.domain;

import edu.iitm.instichain.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/officer")
public class OfficerController {
    private static final Set<String> OFFICERS = Set.of("club_head", "office_bearer");
    private final AccessService access;

    public OfficerController(AccessService access) {
        this.access = access;
    }

    record Club(UUID id, String name, String slug, String position,
                OffsetDateTime startsAt, OffsetDateTime endsAt) {}

    @GetMapping("/clubs")
    List<Club> clubs(HttpServletRequest request) {
        UserAccount user = access.user(request);
        if (user.emailVerifiedAt() == null) return List.of();
        return access.activeAppointments(user, OFFICERS, null).stream()
                .map(appointment -> new Club(appointment.clubId(), appointment.clubName(),
                        appointment.clubSlug(), appointment.position(), appointment.startsAt(),
                        appointment.endsAt()))
                .toList();
    }
}

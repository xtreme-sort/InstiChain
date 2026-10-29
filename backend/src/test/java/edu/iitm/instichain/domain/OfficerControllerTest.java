package edu.iitm.instichain.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.auth.UserAccount;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OfficerControllerTest {
    @Test
    void listsOnlyCurrentOfficerAppointments() {
        AccessService access = mock(AccessService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID userId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        UserAccount user = new UserAccount(userId, "student@smail.iitm.ac.in", "Student", now);
        when(access.user(request)).thenReturn(user);
        when(access.activeAppointments(eq(user), any(), eq(null))).thenReturn(List.of(
                new AccessService.Appointment(UUID.randomUUID(), clubId, userId, "club_head", null,
                        UUID.randomUUID(), now.minusDays(1), now.plusDays(1), null, null,
                        "robotics", "Robotics", null)));

        List<OfficerController.Club> clubs = new OfficerController(access).clubs(request);

        assertEquals(1, clubs.size());
        assertEquals(clubId, clubs.getFirst().id());
        verify(access).activeAppointments(user, Set.of("club_head", "office_bearer"), null);
    }

    @Test
    void unverifiedAccountSeesNoOfficerClubs() {
        AccessService access = mock(AccessService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(access.user(request)).thenReturn(new UserAccount(UUID.randomUUID(),
                "student@smail.iitm.ac.in", "Student", null));
        assertEquals(List.of(), new OfficerController(access).clubs(request));
    }
}

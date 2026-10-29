package edu.iitm.instichain.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;

class StudentCredentialControllerTest {
    @Test
    void statusDoesNotDependOnProofAvailability() {
        CredentialStatus status = new CredentialStatus();
        assertEquals("active", status.current(null, false));
        assertEquals("superseded", status.current(null, true));
        assertEquals("revoked", status.current(OffsetDateTime.now(), true));
    }

    @Test
    void listAndDetailAreScopedToCurrentStudent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccessService access = mock(AccessService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID studentId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(access.user(request)).thenReturn(new UserAccount(studentId,
                "student@smail.iitm.ac.in", "Student", OffsetDateTime.now()));
        StudentCredentialController controller = new StudentCredentialController(jdbc, access,
                new CredentialStatus());

        assertEquals(List.of(), controller.list(request));
        assertThrows(ApiException.class, () -> controller.read(credentialId, request));
        verify(jdbc).query(any(String.class), any(RowMapper.class), eq(studentId));
        verify(jdbc).query(any(String.class), any(RowMapper.class), eq(credentialId), eq(studentId));
    }
}

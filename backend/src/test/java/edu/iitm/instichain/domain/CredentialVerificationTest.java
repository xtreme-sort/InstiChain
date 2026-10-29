package edu.iitm.instichain.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.fabric.FabricService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class CredentialVerificationTest {
    @Test
    void missingReceiptIsUnknownRatherThanValid() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID credentialId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID issuanceId = UUID.randomUUID();
        CredentialVerificationService service = new CredentialVerificationService(jdbc,
                mock(CanonicalJson.class), mock(FabricService.class), new CredentialStatus());
        CredentialVerificationService.Subject subject = new CredentialVerificationService.Subject(
                credentialId, recipientId, UUID.randomUUID(), UUID.randomUUID(), issuanceId,
                OffsetDateTime.now(), null, null, false);
        assertEquals("unknown", service.verify(subject).authenticity());
        assertEquals("missing_receipt", service.verify(subject).ledgerStatus());
    }

    @Test
    void otherStudentCannotReadVerificationOrHistory() {
        AccessService access = mock(AccessService.class);
        CredentialVerificationService service = mock(CredentialVerificationService.class);
        CredentialVerificationController controller = new CredentialVerificationController(access, service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID credentialId = UUID.randomUUID();
        when(access.user(request)).thenReturn(new UserAccount(UUID.randomUUID(),
                "other@smail.iitm.ac.in", "Other", OffsetDateTime.now()));
        when(service.subject(credentialId)).thenReturn(new CredentialVerificationService.Subject(
                credentialId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), OffsetDateTime.now(), null, null, false));
        assertThrows(ApiException.class, () -> controller.studentVerification(credentialId, request));
        assertThrows(ApiException.class, () -> controller.studentHistory(credentialId, request));
    }
}

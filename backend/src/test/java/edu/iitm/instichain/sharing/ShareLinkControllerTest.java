package edu.iitm.instichain.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.auth.SessionService;
import edu.iitm.instichain.auth.UserAccount;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ShareLinkControllerTest {
    private final SessionService sessions = mock(SessionService.class);
    private final ShareLinkService shares = mock(ShareLinkService.class);
    private final ShareLinkController controller = new ShareLinkController(sessions, shares);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final UUID owner = UUID.randomUUID();

    private void signedIn() {
        when(sessions.requireUser(request)).thenReturn(new UserAccount(owner,
                "student@smail.iitm.ac.in", "Student", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void createRequiresSessionAndSameOriginAndDisablesCaching() {
        signedIn();
        UUID requestId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        ShareLinkController.CreateRequest body = new ShareLinkController.CreateRequest(
                requestId, "For recruiter", expiry, List.of(credentialId));
        ShareLinkService.Created result = new ShareLinkService.Created(UUID.randomUUID(),
                "secret", expiry, List.of(credentialId));
        when(shares.create(owner, requestId, body.label(), expiry, body.credentialIds()))
                .thenReturn(result);
        var response = controller.create(body, request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(result, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(sessions).requireSameOrigin(request);
    }

    @Test
    void listAndRevokeRemainOwnerScoped() {
        signedIn();
        assertEquals(List.of(), controller.list(request));
        verify(shares).list(owner);
        UUID shareId = UUID.randomUUID();
        assertEquals(HttpStatus.NO_CONTENT, controller.revoke(shareId, request).getStatusCode());
        verify(sessions).requireSameOrigin(request);
        verify(shares).revoke(owner, shareId);
    }
}

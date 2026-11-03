package edu.iitm.instichain.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.auth.SessionService;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.config.InstiChainProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GoogleStorageControllerTest {
    @Test
    void connectUsesAuthenticatedUserAndSameOriginGuard() {
        SessionService sessions = mock(SessionService.class);
        GoogleConnectionService connection = mock(GoogleConnectionService.class);
        InstiChainProperties properties = new InstiChainProperties("InstiChain",
                "http://127.0.0.1:5173", 15, 20, 30, null, null, null, "", "", "",
                Path.of(".private/proofs"), 1024, "", "", "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID userId = UUID.randomUUID();
        when(sessions.requireUser(request)).thenReturn(new UserAccount(userId,
                "student@smail.iitm.ac.in", "Student", OffsetDateTime.now()));
        when(connection.begin(userId)).thenReturn(URI.create("https://accounts.google.com/test"));
        assertEquals("https://accounts.google.com/test",
                new GoogleStorageController(sessions, connection, properties)
                        .connect(request).get("authorization_url"));
        verify(sessions).requireSameOrigin(request);
    }
}

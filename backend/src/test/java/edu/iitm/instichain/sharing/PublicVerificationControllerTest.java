package edu.iitm.instichain.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicVerificationControllerTest {
    private final PublicVerificationService service = mock(PublicVerificationService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void responsesAreNeverCached() {
        PublicVerificationController controller =
                new PublicVerificationController(service, new PublicRateLimiter());
        String token = "b".repeat(43);
        var result = new PublicVerificationService.Result(null, "Student", null, List.of());
        when(service.verify(token)).thenReturn(result);
        var entity = controller.verify(new PublicVerificationController.VerifyRequest(token), request, response);
        assertEquals(result, entity.getBody());
        assertEquals("no-store", entity.getHeaders().getCacheControl());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
    }

    @Test
    void rateLimitIsEnforcedBeforeAnyLookup() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
        PublicVerificationController controller =
                new PublicVerificationController(service, new PublicRateLimiter(fixed));
        String token = "c".repeat(43);
        for (int attempt = 0; attempt < PublicRateLimiter.LIMIT_PER_MINUTE; attempt++) {
            controller.verify(new PublicVerificationController.VerifyRequest(token), request, response);
        }
        ApiException error = assertThrows(ApiException.class, () -> controller.verify(
                new PublicVerificationController.VerifyRequest(token), request, response));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void limitIsPerClientAndResetsAfterTheWindow() {
        Instant start = Instant.parse("2026-11-07T10:00:00Z");
        Clock[] clock = {Clock.fixed(start, ZoneOffset.UTC)};
        PublicRateLimiter limiter = new PublicRateLimiter(new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        });
        for (int attempt = 0; attempt < PublicRateLimiter.LIMIT_PER_MINUTE; attempt++) limiter.check("1.1.1.1");
        assertThrows(ApiException.class, () -> limiter.check("1.1.1.1"));
        limiter.check("2.2.2.2");
        clock[0] = Clock.fixed(start.plusSeconds(61), ZoneOffset.UTC);
        limiter.check("1.1.1.1");
        verify(service, never()).verify("unused");
    }
}

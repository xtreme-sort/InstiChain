package edu.iitm.instichain.sharing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated verifier endpoint. The token travels in the request body, not the URL,
 * so it never reaches access logs or Referer headers.
 */
@RestController
@RequestMapping("/api/public/verification")
public class PublicVerificationController {
    private final PublicVerificationService verification;
    private final PublicRateLimiter limiter;

    public PublicVerificationController(PublicVerificationService verification,
                                        PublicRateLimiter limiter) {
        this.verification = verification;
        this.limiter = limiter;
    }

    record VerifyRequest(@NotNull @Size(max = 100) String token) {}

    @PostMapping
    ResponseEntity<PublicVerificationService.Result> verify(@Valid @RequestBody VerifyRequest body,
                                                            HttpServletRequest request,
                                                            HttpServletResponse response) {
        // Set before any check so error responses are never cached either.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        limiter.check(request.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(verification.verify(body.token()));
    }
}

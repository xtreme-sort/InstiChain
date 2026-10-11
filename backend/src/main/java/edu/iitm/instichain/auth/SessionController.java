package edu.iitm.instichain.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/session")
    ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("id", user.id(), "email", user.email(), "display_name", user.displayName()));
    }

    @PostMapping("/logout")
    @Transactional
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.revoke(sessions.cookie(request));
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, "")
                .httpOnly(true).secure(sessions.secureCookie()).sameSite("Lax")
                .path("/").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }
}

package edu.iitm.instichain.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/signing-key")
public class SigningKeyController {
    private final SessionService sessions;
    private final SigningKeyService signingKeys;

    public SigningKeyController(SessionService sessions, SigningKeyService signingKeys) {
        this.sessions = sessions;
        this.signingKeys = signingKeys;
    }

    record RegisterRequest(
            @JsonProperty("public_key")
            @NotBlank @Size(min = 44, max = 44) String publicKey) {}

    @GetMapping
    ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        Map<String, Object> body = signingKeys.findActive(user.id())
                .map(signingKeys::response)
                .orElseGet(() -> Map.of("registered", false));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest body,
                                                  HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        sessions.requireSameOrigin(request);
        boolean created = signingKeys.findActive(user.id()).isEmpty();
        SigningKeyService.SigningKey key = signingKeys.register(user.id(), body.publicKey());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(signingKeys.response(key));
    }
}

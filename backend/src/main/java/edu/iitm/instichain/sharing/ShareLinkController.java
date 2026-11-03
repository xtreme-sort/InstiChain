package edu.iitm.instichain.sharing;

import edu.iitm.instichain.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shares")
public class ShareLinkController {
    private final SessionService sessions;
    private final ShareLinkService shares;

    public ShareLinkController(SessionService sessions, ShareLinkService shares) {
        this.sessions = sessions;
        this.shares = shares;
    }

    record CreateRequest(@NotNull UUID requestId, @Size(max = 120) String label,
                         @NotNull OffsetDateTime expiresAt,
                         @NotNull @Size(min = 1, max = 50) List<@NotNull UUID> credentialIds) {}

    @PostMapping
    ResponseEntity<ShareLinkService.Created> create(@Valid @RequestBody CreateRequest body,
                                                    HttpServletRequest request) {
        UUID ownerId = sessions.requireUser(request).id();
        sessions.requireSameOrigin(request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(shares.create(ownerId, body.requestId(),
                body.label(), body.expiresAt(), body.credentialIds()));
    }

    @GetMapping
    List<ShareLinkService.Summary> list(HttpServletRequest request) {
        return shares.list(sessions.requireUser(request).id());
    }

    @PostMapping("/{shareId}/revoke")
    ResponseEntity<Void> revoke(@PathVariable UUID shareId, HttpServletRequest request) {
        UUID ownerId = sessions.requireUser(request).id();
        sessions.requireSameOrigin(request);
        shares.revoke(ownerId, shareId);
        return ResponseEntity.noContent().build();
    }
}

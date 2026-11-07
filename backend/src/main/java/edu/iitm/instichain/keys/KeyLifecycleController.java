package edu.iitm.instichain.keys;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.domain.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KeyLifecycleController {
    private final AccessService access;
    private final KeyLifecycleService lifecycle;
    private final KeyRepository keys;

    public KeyLifecycleController(AccessService access, KeyLifecycleService lifecycle, KeyRepository keys) {
        this.access = access;
        this.lifecycle = lifecycle;
        this.keys = keys;
    }

    record NewKey(@NotBlank @Size(min = 44, max = 44) String newPublicKey) {}
    record SignedRotation(@NotNull JsonNode document, @NotBlank @Size(max = 88) String signature,
                          @NotBlank @Size(max = 88) String newKeySignature) {}
    record RecoveryRequest(@NotBlank @Size(min = 44, max = 44) String publicKey,
                           @NotBlank @Size(max = 88) String proof) {}
    record Approval(@NotBlank @Size(max = 500) String verificationReference) {}
    record Compromise(@NotBlank @Size(max = 500) String reason, OffsetDateTime compromisedAt) {}

    @GetMapping("/api/keys/status")
    ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        UUID userId = access.user(request).id();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("advisor_key", keys.currentAdvisorKey(userId).map(key -> Map.of(
                "id", key.id(), "public_key", Base64.getEncoder().encodeToString(key.publicKey()),
                "created_at", key.createdAt())).orElse(null));
        body.put("pending_recovery", keys.pendingRecoveryFor(userId).map(pending -> Map.of(
                "id", pending.id(), "public_key", Base64.getEncoder().encodeToString(pending.publicKey()),
                "expires_at", pending.expiresAt())).orElse(null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/api/keys/rotation/prepare")
    KeyLifecycleService.Prepared prepareRotation(@Valid @RequestBody NewKey body, HttpServletRequest request) {
        UserAccount user = access.user(request);
        return lifecycle.prepareRotation(user.id(), body.newPublicKey());
    }

    @PostMapping("/api/keys/rotation")
    @Transactional
    ResponseEntity<KeyLifecycleService.Rotated> rotate(@Valid @RequestBody SignedRotation body,
                                                       HttpServletRequest request) {
        UserAccount user = access.user(request);
        access.mutationGuard(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(lifecycle.rotate(user.id(),
                body.document(), body.signature(), body.newKeySignature()));
    }

    @PostMapping("/api/keys/recovery-requests")
    @Transactional
    ResponseEntity<KeyLifecycleService.Recovery> requestRecovery(@Valid @RequestBody RecoveryRequest body,
                                                                 HttpServletRequest request) {
        UserAccount user = access.user(request);
        access.mutationGuard(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(lifecycle.requestRecovery(user.id(), body.publicKey(), body.proof()));
    }

    @GetMapping("/api/admin/key-recovery-requests")
    List<Map<String, Object>> pendingRecoveries(HttpServletRequest request) {
        access.admin(request);
        return keys.pendingRecoveries().stream().map(pending -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pending.id());
            row.put("user_id", pending.userId());
            row.put("email", pending.email());
            row.put("display_name", pending.displayName());
            row.put("public_key", Base64.getEncoder().encodeToString(pending.publicKey()));
            row.put("created_at", pending.createdAt());
            row.put("expires_at", pending.expiresAt());
            row.put("advisor_key", keys.currentAdvisorKey(pending.userId()).map(key -> key.id()).orElse(null));
            return row;
        }).toList();
    }

    @PostMapping("/api/admin/key-recovery-requests/{requestId}/approve")
    @Transactional
    KeyLifecycleService.Rotated approve(@PathVariable UUID requestId, @Valid @RequestBody Approval body,
                                        HttpServletRequest request) {
        return lifecycle.approveRecovery(access.admin(request), requestId, body.verificationReference());
    }

    @PostMapping("/api/admin/key-recovery-requests/{requestId}/cancel")
    @Transactional
    ResponseEntity<Void> cancel(@PathVariable UUID requestId, HttpServletRequest request) {
        lifecycle.cancelRecovery(access.admin(request), requestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/keys/{keyId}/revocation")
    @Transactional
    KeyLifecycleService.Revoked revoke(@PathVariable UUID keyId, @Valid @RequestBody Compromise body,
                                       HttpServletRequest request) {
        return lifecycle.revokeCompromised(access.admin(request), keyId, body.reason(), body.compromisedAt());
    }
}

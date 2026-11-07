package edu.iitm.instichain.keys;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.common.UuidV5;
import edu.iitm.instichain.domain.AdminController;
import edu.iitm.instichain.domain.AccessService;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import edu.iitm.instichain.fabric.LedgerEventService;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Signing-key rotation, device-loss recovery and compromise handling. Every change is
 * recorded on Fabric before PostgreSQL is updated; retired public keys are kept so
 * signatures made before the change still verify.
 */
@Service
public class KeyLifecycleService {
    static final String RECOVERY_PURPOSE = "instichain-key-recovery";
    static final long RECOVERY_HOURS = 72;
    static final int RECOVERY_REQUESTS_PER_DAY = 5;
    private final KeyRepository keys;
    private final LedgerEventService ledger;
    private final CanonicalJson json;

    public KeyLifecycleService(KeyRepository keys, LedgerEventService ledger, CanonicalJson json) {
        this.keys = keys;
        this.ledger = ledger;
        this.json = json;
    }

    public record Prepared(Map<String, Object> document, String signingPayload) {}
    public record Rotated(UUID oldKeyId, UUID newKeyId, OffsetDateTime rotatedAt, UUID transactionId) {}
    public record Recovery(UUID id, OffsetDateTime expiresAt) {}
    public record Revoked(UUID keyId, OffsetDateTime revokedAt, UUID transactionId,
                          List<KeyRepository.AffectedCredential> credentialsForReview) {}

    // ---- Rotation: the advisor proves possession of both the old and the new key. ----

    public Prepared prepareRotation(UUID userId, String encodedNewKey) {
        KeyRepository.AuthorityKey current = keys.currentAdvisorKey(userId).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN,
                        "Only an advisor with an active signing key can rotate it. Request a recovery instead."));
        byte[] newKey = decode(encodedNewKey);
        if (keys.publicKeyKnown(newKey)) throw knownKey();
        UUID newKeyId = KeyRepository.advisorKeyId(newKey);
        Map<String, Object> document = AdminController.action(rotationId(current.id(), newKeyId),
                "KEY_ROTATED", Map.of("user_id", userId, "old_key_id", current.id(), "new_key_id", newKeyId),
                Map.of("new_public_key", encodedNewKey, "prepared_at", keys.now()), userId, current.id());
        return new Prepared(document, Base64.getEncoder().encodeToString(json.bytes(document)));
    }

    public Rotated rotate(UUID userId, JsonNode document, String encodedOldSignature,
                          String encodedNewSignature) {
        KeyRepository.AuthorityKey current = keys.currentAdvisorKey(userId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "This signing key has already been rotated or revoked."));
        JsonNode objects = document.path("object_ids");
        JsonNode payload = document.path("payload");
        byte[] newKey = decode(payload.path("new_public_key").asText());
        UUID newKeyId = KeyRepository.advisorKeyId(newKey);
        if (!"KEY_ROTATED".equals(document.path("event_type").asText())
                || !uuid(document, "request_id").equals(rotationId(current.id(), newKeyId))
                || !uuid(document, "actor_id").equals(userId) || !uuid(objects, "user_id").equals(userId)
                || !uuid(document, "actor_key_id").equals(current.id())
                || !uuid(objects, "old_key_id").equals(current.id())
                || !uuid(objects, "new_key_id").equals(newKeyId)) {
            throw invalid("The signed rotation does not match your current signing key.");
        }
        requireFresh(time(payload, "prepared_at"));
        byte[] bytes = json.bytes(document);
        byte[] oldSignature = signature(encodedOldSignature);
        if (!Ed25519.verify(current.publicKey(), bytes, oldSignature)) {
            throw invalid("The rotation was not signed by your current key.");
        }
        // Signing the same bytes with the new key proves the advisor actually holds it.
        if (!Ed25519.verify(newKey, bytes, signature(encodedNewSignature))) {
            throw invalid("The new key did not prove possession by signing the rotation.");
        }
        if (keys.publicKeyKnown(newKey)) throw knownKey();
        LedgerEventService.Event event = append(() -> ledger.append(document, oldSignature, current.id()),
                "no key was rotated");
        keys.insertAdvisorKey(newKeyId, userId, newKey, userId,
                "Rotated by the advisor from key " + current.id(), event.committedAt(), current.id());
        keys.retireAuthorityKey(current.id(), event.committedAt(), event.requestId(), "rotated");
        keys.replaceUserSigningKey(userId, newKey);
        return new Rotated(current.id(), newKeyId, event.committedAt(), event.requestId());
    }

    // ---- Recovery: a lost device needs an administrator's out-of-band check. ----

    /** The bytes a browser signs to prove it holds the key it asks to have approved. */
    public byte[] recoveryProof(UUID userId, String encodedKey) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("purpose", RECOVERY_PURPOSE);
        proof.put("user_id", userId);
        proof.put("public_key", encodedKey);
        return json.bytes(proof);
    }

    public Recovery requestRecovery(UUID userId, String encodedKey, String encodedProof) {
        if (keys.recoveryRequestsToday(userId) >= RECOVERY_REQUESTS_PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many recovery requests today. Contact the administrator.");
        }
        byte[] newKey = decode(encodedKey);
        if (!Ed25519.verify(newKey, recoveryProof(userId, encodedKey), signature(encodedProof))) {
            throw invalid("The new key did not prove possession.");
        }
        // Replacing an earlier pending request lets a user retry from another device.
        keys.cancelPendingRecovery(userId, userId);
        if (keys.publicKeyKnown(newKey)) throw knownKey();
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = keys.now().plusHours(RECOVERY_HOURS);
        keys.insertRecovery(id, userId, newKey, expiresAt);
        return new Recovery(id, expiresAt);
    }

    public Rotated approveRecovery(AccessService.Administrator admin, UUID requestId, String reference) {
        String verification = requireReference(reference);
        KeyRepository.RecoveryRequest request = keys.pendingRecovery(requestId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No pending recovery request was found."));
        String encodedKey = Base64.getEncoder().encodeToString(request.publicKey());
        UUID newKeyId = KeyRepository.advisorKeyId(request.publicKey());
        List<UUID> retired = new ArrayList<>(keys.activeAdvisorKeyIds(request.userId()));
        UUID previous = keys.latestAdvisorKeyId(request.userId()).orElse(null);
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("user_id", request.userId());
        objects.put("recovery_request_id", requestId);
        objects.put("revoked_key_ids", retired);
        objects.put("new_key_id", newKeyId);
        Map<String, Object> document = AdminController.action(
                UuidV5.url("instichain:key-recovery:" + requestId), "KEY_RECOVERED", objects,
                Map.of("new_public_key", encodedKey, "verification_reference", verification),
                admin.user().id(), admin.keyId());
        LedgerEventService.Event event = append(() ->
                ledger.appendAdmin(document, admin.keyId(), admin.publicKey()), "no key was recovered");
        for (UUID key : retired) keys.retireAuthorityKey(key, event.committedAt(), event.requestId(), "recovered");
        // Only someone who has held advisor authority needs an advisor key; others just get a new account key.
        if (previous != null) {
            keys.insertAdvisorKey(newKeyId, request.userId(), request.publicKey(), admin.user().id(),
                    verification, event.committedAt(), previous);
        }
        try {
            keys.replaceUserSigningKey(request.userId(), request.publicKey());
        } catch (DuplicateKeyException duplicate) {
            throw knownKey();
        }
        keys.resolveRecovery(requestId, "approved", admin.user().id(), event.requestId());
        return new Rotated(previous, newKeyId, event.committedAt(), event.requestId());
    }

    public void cancelRecovery(AccessService.Administrator admin, UUID requestId) {
        KeyRepository.RecoveryRequest request = keys.pendingRecovery(requestId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No pending recovery request was found."));
        keys.resolveRecovery(request.id(), "cancelled", admin.user().id(), null);
    }

    // ---- Compromise: revoke from the moment of compromise, then review what it signed. ----

    public Revoked revokeCompromised(AccessService.Administrator admin, UUID keyId, String reason,
                                     OffsetDateTime compromisedAt) {
        String why = requireReference(reason);
        KeyRepository.AuthorityKey key = keys.advisorKey(keyId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Advisor key not found."));
        if (key.revokedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "This key is already revoked.");
        OffsetDateTime now = keys.now();
        OffsetDateTime from = compromisedAt == null ? now : compromisedAt;
        if (from.isAfter(now) || from.isBefore(key.createdAt())) {
            throw invalid("The compromise time must be between the key's creation and now.");
        }
        Map<String, Object> document = AdminController.action(
                UuidV5.url("instichain:key-revocation:" + keyId), "KEY_REVOKED",
                Map.of("user_id", key.userId(), "key_id", keyId),
                Map.of("reason", why, "compromised_at", from), admin.user().id(), admin.keyId());
        LedgerEventService.Event event = append(() ->
                ledger.appendAdmin(document, admin.keyId(), admin.publicKey()), "no key was revoked");
        // Backdating to the compromise makes later signatures fail historical authority checks.
        keys.retireAuthorityKey(keyId, from, event.requestId(), "compromised");
        keys.retireUserSigningKey(key.publicKey());
        return new Revoked(keyId, from, event.requestId(), keys.credentialsSignedSince(keyId, from));
    }

    // ---- helpers ----

    private static UUID rotationId(UUID oldKeyId, UUID newKeyId) {
        return UuidV5.url("instichain:key-rotation:" + oldKeyId + ":" + newKeyId);
    }

    private void requireFresh(OffsetDateTime preparedAt) {
        OffsetDateTime now = keys.now();
        if (preparedAt.isAfter(now) || preparedAt.isBefore(now.minusMinutes(10))) {
            throw invalid("The signed rotation has expired. Prepare it again.");
        }
    }

    private interface Append { LedgerEventService.Event run(); }

    private static LedgerEventService.Event append(Append operation, String outcome) {
        try {
            return operation.run();
        } catch (FabricUnavailableException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The Fabric ledger is unavailable; " + outcome + ".");
        }
    }

    static byte[] decode(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (raw.length != 32 || java.util.Arrays.equals(raw, new byte[32])) throw new IllegalArgumentException();
            return raw;
        } catch (IllegalArgumentException error) {
            throw invalid("Provide a valid 32-byte Ed25519 public key.");
        }
    }

    private static byte[] signature(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (raw.length != 64) throw new IllegalArgumentException();
            return raw;
        } catch (IllegalArgumentException error) {
            throw invalid("Provide a base64 Ed25519 signature.");
        }
    }

    private static String requireReference(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > 500) throw invalid("Provide a reference of 1 to 500 characters.");
        return text;
    }

    private static UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(node.path(field).asText()); }
        catch (IllegalArgumentException error) { throw invalid("The signed document has an invalid " + field + "."); }
    }

    private static OffsetDateTime time(JsonNode node, String field) {
        try { return OffsetDateTime.parse(node.path(field).asText()); }
        catch (DateTimeParseException error) { throw invalid("The signed document has an invalid " + field + "."); }
    }

    private static ApiException knownKey() {
        return new ApiException(HttpStatus.CONFLICT,
                "This public key is already registered or was retired. Generate a new key.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

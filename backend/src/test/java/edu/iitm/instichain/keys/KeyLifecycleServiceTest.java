package edu.iitm.instichain.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.common.Ed25519;
import edu.iitm.instichain.domain.AccessService;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import edu.iitm.instichain.fabric.LedgerEventService;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KeyLifecycleServiceTest {
    private final KeyRepository keys = mock(KeyRepository.class);
    private final LedgerEventService ledger = mock(LedgerEventService.class);
    private final CanonicalJson json = new CanonicalJson(new ObjectMapper()
            .registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final KeyLifecycleService service = new KeyLifecycleService(keys, ledger, json);
    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    private PrivateKey oldPrivate;
    private PrivateKey newPrivate;
    private KeyRepository.AuthorityKey oldKey;
    private String newPublic;

    private static PrivateKey generate() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
    }

    private String sign(PrivateKey key, byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(Ed25519.sign(key, bytes));
    }

    @BeforeEach
    void advisorWithActiveKey() throws Exception {
        oldPrivate = generate();
        newPrivate = generate();
        byte[] oldPublic = Ed25519.rawPublic(oldPrivate);
        oldKey = new KeyRepository.AuthorityKey(KeyRepository.advisorKeyId(oldPublic), userId,
                oldPublic, now.minusDays(30), null);
        newPublic = Base64.getEncoder().encodeToString(Ed25519.rawPublic(newPrivate));
        when(keys.currentAdvisorKey(userId)).thenReturn(Optional.of(oldKey));
        when(keys.now()).thenReturn(now);
        when(ledger.append(any(), any(), any())).thenAnswer(call -> new LedgerEventService.Event(
                UUID.randomUUID(), now.plusSeconds(1), null));
    }

    private JsonNode prepared() {
        return json.tree(service.prepareRotation(userId, newPublic).document());
    }

    @Test
    void rotationRecordsOnFabricThenRetiresTheOldKeyAndKeepsIt() throws Exception {
        JsonNode document = prepared();
        byte[] bytes = json.bytes(document);
        KeyLifecycleService.Rotated rotated = service.rotate(userId, document,
                sign(oldPrivate, bytes), sign(newPrivate, bytes));
        UUID newKeyId = KeyRepository.advisorKeyId(Base64.getDecoder().decode(newPublic));
        assertEquals(oldKey.id(), rotated.oldKeyId());
        assertEquals(newKeyId, rotated.newKeyId());
        verify(ledger).append(eq(document), any(), eq(oldKey.id()));
        verify(keys).insertAdvisorKey(eq(newKeyId), eq(userId), any(), eq(userId), anyString(),
                eq(rotated.rotatedAt()), eq(oldKey.id()));
        // Retired, not deleted: the old public key stays for historical verification.
        verify(keys).retireAuthorityKey(oldKey.id(), rotated.rotatedAt(), rotated.transactionId(), "rotated");
        verify(keys).replaceUserSigningKey(eq(userId), any());
    }

    @Test
    void rotationRequiresProofOfPossessionOfTheNewKey() throws Exception {
        JsonNode document = prepared();
        byte[] bytes = json.bytes(document);
        ApiException error = assertThrows(ApiException.class, () -> service.rotate(userId, document,
                sign(oldPrivate, bytes), sign(generate(), bytes)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status());
        verify(ledger, never()).append(any(), any(), any());
    }

    @Test
    void rotationMustBeSignedByTheCurrentKey() throws Exception {
        JsonNode document = prepared();
        byte[] bytes = json.bytes(document);
        assertThrows(ApiException.class, () -> service.rotate(userId, document,
                sign(generate(), bytes), sign(newPrivate, bytes)));
        verify(ledger, never()).append(any(), any(), any());
    }

    @Test
    void substitutingTheNewKeyAfterSigningIsRejected() throws Exception {
        ObjectNode document = (ObjectNode) prepared();
        PrivateKey attacker = generate();
        ((ObjectNode) document.path("payload")).put("new_public_key",
                Base64.getEncoder().encodeToString(Ed25519.rawPublic(attacker)));
        byte[] bytes = json.bytes(document);
        assertThrows(ApiException.class, () -> service.rotate(userId, document,
                sign(oldPrivate, bytes), sign(attacker, bytes)));
    }

    @Test
    void staleRotationIsRejected() throws Exception {
        JsonNode document = prepared();
        when(keys.now()).thenReturn(now.plusMinutes(11));
        byte[] bytes = json.bytes(document);
        assertThrows(ApiException.class, () -> service.rotate(userId, document,
                sign(oldPrivate, bytes), sign(newPrivate, bytes)));
    }

    @Test
    void fabricOutageLeavesKeysUnchanged() throws Exception {
        JsonNode document = prepared();
        byte[] bytes = json.bytes(document);
        when(ledger.append(any(), any(), any())).thenThrow(new FabricUnavailableException("down"));
        ApiException error = assertThrows(ApiException.class, () -> service.rotate(userId, document,
                sign(oldPrivate, bytes), sign(newPrivate, bytes)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        verify(keys, never()).retireAuthorityKey(any(), any(), any(), any());
    }

    @Test
    void retiredOrRegisteredKeysCannotBeReused() {
        when(keys.publicKeyKnown(any())).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> service.prepareRotation(userId, newPublic)).status());
    }

    @Test
    void nonAdvisorCannotRotate() {
        when(keys.currentAdvisorKey(userId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.prepareRotation(userId, newPublic)).status());
    }

    @Test
    void recoveryRequestNeedsProofFromTheNewKey() throws Exception {
        byte[] proof = service.recoveryProof(userId, newPublic);
        assertThrows(ApiException.class, () -> service.requestRecovery(userId, newPublic, sign(generate(), proof)));
        verify(keys, never()).insertRecovery(any(), any(), any(), any());
        KeyLifecycleService.Recovery recovery = service.requestRecovery(userId, newPublic, sign(newPrivate, proof));
        assertEquals(now.plusHours(KeyLifecycleService.RECOVERY_HOURS), recovery.expiresAt());
        verify(keys).cancelPendingRecovery(userId, userId);
    }

    private AccessService.Administrator admin() {
        return new AccessService.Administrator(new UserAccount(UUID.randomUUID(), "admin@iitm.ac.in",
                "Admin", now), UUID.randomUUID(), new byte[32]);
    }

    private KeyRepository.RecoveryRequest pending(UUID requestId) {
        return new KeyRepository.RecoveryRequest(requestId, userId, "advisor@iitm.ac.in", "Advisor",
                Base64.getDecoder().decode(newPublic), now, now.plusHours(72));
    }

    @Test
    void approvedRecoveryRetiresLostKeysAndContinuesTheChain() {
        UUID requestId = UUID.randomUUID();
        AccessService.Administrator admin = admin();
        when(keys.pendingRecovery(requestId)).thenReturn(Optional.of(pending(requestId)));
        when(keys.activeAdvisorKeyIds(userId)).thenReturn(List.of(oldKey.id()));
        when(keys.latestAdvisorKeyId(userId)).thenReturn(Optional.of(oldKey.id()));
        when(ledger.appendAdmin(any(), eq(admin.keyId()), any())).thenAnswer(call ->
                new LedgerEventService.Event(UUID.randomUUID(), now.plusSeconds(1), null));
        KeyLifecycleService.Rotated result = service.approveRecovery(admin, requestId, "Faculty office ticket 42");
        verify(keys).retireAuthorityKey(oldKey.id(), result.rotatedAt(), result.transactionId(), "recovered");
        verify(keys).insertAdvisorKey(eq(result.newKeyId()), eq(userId), any(), eq(admin.user().id()),
                eq("Faculty office ticket 42"), eq(result.rotatedAt()), eq(oldKey.id()));
        verify(keys).resolveRecovery(requestId, "approved", admin.user().id(), result.transactionId());
    }

    @Test
    void recoveryForSomeoneWhoWasNeverAnAdvisorGrantsNoAdvisorKey() {
        UUID requestId = UUID.randomUUID();
        AccessService.Administrator admin = admin();
        when(keys.pendingRecovery(requestId)).thenReturn(Optional.of(pending(requestId)));
        when(keys.activeAdvisorKeyIds(userId)).thenReturn(List.of());
        when(keys.latestAdvisorKeyId(userId)).thenReturn(Optional.empty());
        when(ledger.appendAdmin(any(), any(), any())).thenAnswer(call ->
                new LedgerEventService.Event(UUID.randomUUID(), now, null));
        assertNull(service.approveRecovery(admin, requestId, "Ticket 7").oldKeyId());
        verify(keys, never()).insertAdvisorKey(any(), any(), any(), any(), any(), any(), any());
        verify(keys).replaceUserSigningKey(eq(userId), any());
    }

    @Test
    void compromiseIsBackdatedAndReturnsCredentialsToReview() {
        AccessService.Administrator admin = admin();
        OffsetDateTime compromised = now.minusDays(2);
        when(keys.advisorKey(oldKey.id())).thenReturn(Optional.of(oldKey));
        when(ledger.appendAdmin(any(), any(), any())).thenAnswer(call ->
                new LedgerEventService.Event(UUID.randomUUID(), now, null));
        KeyRepository.AffectedCredential affected = new KeyRepository.AffectedCredential(
                UUID.randomUUID(), "First place", "Hackathon", now.minusDays(1));
        when(keys.credentialsSignedSince(oldKey.id(), compromised)).thenReturn(List.of(affected));
        KeyLifecycleService.Revoked revoked = service.revokeCompromised(admin, oldKey.id(), "Laptop stolen", compromised);
        assertEquals(compromised, revoked.revokedAt());
        assertEquals(List.of(affected), revoked.credentialsForReview());
        verify(keys).retireAuthorityKey(oldKey.id(), compromised, revoked.transactionId(), "compromised");
    }

    @Test
    void compromiseTimeMustFallWithinTheKeysLifetime() {
        when(keys.advisorKey(oldKey.id())).thenReturn(Optional.of(oldKey));
        assertThrows(ApiException.class, () -> service.revokeCompromised(admin(), oldKey.id(), "x", now.plusDays(1)));
        assertThrows(ApiException.class, () -> service.revokeCompromised(admin(), oldKey.id(), "x", now.minusDays(31)));
        verify(ledger, never()).appendAdmin(any(), any(), any());
    }
}

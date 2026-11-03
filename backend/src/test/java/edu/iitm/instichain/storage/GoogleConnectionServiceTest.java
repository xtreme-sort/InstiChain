package edu.iitm.instichain.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class GoogleConnectionServiceTest {
    private static StorageCrypto crypto() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new StorageCrypto(new InstiChainProperties("InstiChain", "http://127.0.0.1:5173",
                15, 20, 30, null, null, null, "", "", key, Path.of(".private/proofs"),
                1024, "", "", ""), new MockEnvironment());
    }

    @Test
    void stateIsOpaqueAndSingleUse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StorageRepository storage = mock(StorageRepository.class);
        GoogleOAuthGateway gateway = mock(GoogleOAuthGateway.class);
        GoogleConnectionService service = new GoogleConnectionService(jdbc, storage, crypto(), gateway);
        UUID owner = UUID.randomUUID();
        when(gateway.authorizationUrl(anyString())).thenAnswer(invocation ->
                URI.create("https://accounts.google.com/oauth?state=" + invocation.getArgument(0)));
        URI url = service.begin(owner);
        String state = url.getQuery().substring("state=".length());
        assertEquals(43, state.length());
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(owner), anyString()))
                .thenReturn(List.of(UUID.randomUUID()), List.of());
        service.consumeState(owner, state);
        assertThrows(ApiException.class, () -> service.consumeState(owner, state));
    }

    @Test
    void disconnectRemovesLocalSecretEvenIfProviderRevocationFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StorageRepository storage = mock(StorageRepository.class);
        GoogleOAuthGateway gateway = mock(GoogleOAuthGateway.class);
        StorageCrypto crypto = crypto();
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(storage.connection(owner, "google")).thenReturn(new StorageRepository.Connection(
                id, owner, "google", crypto.encrypt("refresh-secret"),
                GoogleOAuthGateway.DRIVE_FILE_SCOPE, "connected", OffsetDateTime.now()));
        org.mockito.Mockito.doThrow(new IllegalStateException("provider failed"))
                .when(gateway).revoke("refresh-secret");
        new GoogleConnectionService(jdbc, storage, crypto, gateway).disconnect(owner);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("encrypted_refresh_token = NULL"),
                eq(id), eq(owner));
    }

    @Test
    void refreshRotationStoresEncryptedReplacement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StorageRepository storage = mock(StorageRepository.class);
        GoogleOAuthGateway gateway = mock(GoogleOAuthGateway.class);
        StorageCrypto crypto = crypto();
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(storage.connection(owner, "google")).thenReturn(new StorageRepository.Connection(
                id, owner, "google", crypto.encrypt("old-refresh"),
                GoogleOAuthGateway.DRIVE_FILE_SCOPE, "connected", OffsetDateTime.now()));
        when(gateway.refresh("old-refresh")).thenReturn(new GoogleOAuthGateway.Tokens(
                "short-lived-access", "new-refresh", GoogleOAuthGateway.DRIVE_FILE_SCOPE));
        assertEquals("short-lived-access", new GoogleConnectionService(jdbc, storage, crypto, gateway)
                .accessToken(owner));
        org.mockito.ArgumentCaptor<byte[]> encrypted = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("encrypted_refresh_token = ?"),
                encrypted.capture(), eq(id), eq(owner));
        assertEquals("new-refresh", crypto.decrypt(encrypted.getValue()));
        assertFalse(new String(encrypted.getValue()).contains("new-refresh"));
    }

    @Test
    void tokenRepresentationIsRedacted() {
        assertFalse(new GoogleOAuthGateway.Tokens("access", "refresh", "scope")
                .toString().contains("refresh"));
    }
}

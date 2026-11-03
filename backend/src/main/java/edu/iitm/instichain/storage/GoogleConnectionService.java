package edu.iitm.instichain.storage;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleConnectionService {
    private final JdbcTemplate jdbc;
    private final StorageRepository storage;
    private final StorageCrypto crypto;
    private final GoogleOAuthGateway gateway;
    private final SecureRandom random = new SecureRandom();

    public GoogleConnectionService(JdbcTemplate jdbc, StorageRepository storage,
                                   StorageCrypto crypto, GoogleOAuthGateway gateway) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.crypto = crypto;
        this.gateway = gateway;
    }

    public record ConnectionStatus(boolean connected, String provider, String status,
                                   String scopes) {}

    public URI begin(UUID ownerId) {
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM storage_oauth_states
                WHERE owner_id = ? AND created_at > clock_timestamp() - interval '1 hour'
                """, Integer.class, ownerId);
        if (recent != null && recent >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many Drive connection attempts.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        URI authorizationUrl = gateway.authorizationUrl(state);
        jdbc.update("""
                INSERT INTO storage_oauth_states(id, owner_id, state_hash, expires_at)
                VALUES (?, ?, ?, clock_timestamp() + interval '10 minutes')
                """, UUID.randomUUID(), ownerId, Hashing.sha256(state));
        return authorizationUrl;
    }

    @Transactional
    public void consumeState(UUID ownerId, String state) {
        if (state == null || !state.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Drive connection state.");
        }
        List<UUID> consumed = jdbc.queryForList("""
                UPDATE storage_oauth_states SET consumed_at = clock_timestamp()
                WHERE owner_id = ? AND state_hash = ? AND consumed_at IS NULL
                  AND expires_at > clock_timestamp()
                RETURNING id
                """, UUID.class, ownerId, Hashing.sha256(state));
        if (consumed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Drive connection state.");
        }
    }

    @Transactional
    public void complete(UUID ownerId, String code) {
        if (code == null || code.isBlank() || code.length() > 2048) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Drive authorization was not completed.");
        }
        GoogleOAuthGateway.Tokens tokens = gateway.exchange(code);
        if (!List.of(tokens.scope().split(" ")).contains(GoogleOAuthGateway.DRIVE_FILE_SCOPE)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Drive did not grant the required file scope.");
        }
        StorageRepository.Connection existing = storage.connection(ownerId, "google");
        String refresh = tokens.refreshToken();
        if (refresh == null || refresh.isBlank()) {
            if (existing == null || existing.encryptedRefreshToken() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Drive did not provide offline access. Reconnect with consent.");
            }
            refresh = crypto.decrypt(existing.encryptedRefreshToken());
        }
        byte[] encrypted = crypto.encrypt(refresh);
        jdbc.update("""
                INSERT INTO storage_connections(id, owner_id, provider, encrypted_refresh_token,
                                                granted_scopes, status)
                VALUES (?, ?, 'google', ?, ?, 'connected')
                ON CONFLICT (owner_id, provider) DO UPDATE SET
                    encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                    granted_scopes = EXCLUDED.granted_scopes, status = 'connected',
                    disconnected_at = NULL, updated_at = clock_timestamp()
                """, UUID.randomUUID(), ownerId, encrypted, tokens.scope());
    }

    public ConnectionStatus status(UUID ownerId) {
        StorageRepository.Connection connection = storage.connection(ownerId, "google");
        return connection == null ? new ConnectionStatus(false, "google", "not_connected", "")
                : new ConnectionStatus("connected".equals(connection.status()), "google",
                connection.status(), connection.scopes());
    }

    @Transactional
    public void disconnect(UUID ownerId) {
        StorageRepository.Connection connection = storage.connection(ownerId, "google");
        if (connection == null || connection.encryptedRefreshToken() == null) return;
        String refresh = crypto.decrypt(connection.encryptedRefreshToken());
        try { gateway.revoke(refresh); }
        catch (RuntimeException ignored) { /* Always remove local access, even if Google is unavailable. */ }
        jdbc.update("""
                UPDATE storage_connections SET encrypted_refresh_token = NULL,
                    status = 'disconnected', disconnected_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ? AND owner_id = ?
                """, connection.id(), ownerId);
    }

    @Transactional
    public String accessToken(UUID ownerId) {
        StorageRepository.Connection connection = storage.connection(ownerId, "google");
        if (connection == null || !"connected".equals(connection.status())
                || connection.encryptedRefreshToken() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Connect Google Drive first.");
        }
        GoogleOAuthGateway.Tokens tokens = gateway.refresh(crypto.decrypt(connection.encryptedRefreshToken()));
        if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
            jdbc.update("""
                    UPDATE storage_connections SET encrypted_refresh_token = ?,
                        updated_at = clock_timestamp() WHERE id = ? AND owner_id = ?
                    """, crypto.encrypt(tokens.refreshToken()), connection.id(), ownerId);
        }
        return tokens.accessToken();
    }
}

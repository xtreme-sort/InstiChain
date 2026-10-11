package edu.iitm.instichain.auth;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.UuidV5;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SigningKeyService {
    private final JdbcTemplate jdbc;

    public SigningKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SigningKey> findActive(UUID userId) {
        List<SigningKey> keys = jdbc.query("""
                SELECT id, user_id, public_key, created_at
                FROM user_signing_keys
                WHERE user_id = ? AND revoked_at IS NULL
                """, (result, row) -> new SigningKey(
                result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getBytes("public_key"),
                result.getObject("created_at", OffsetDateTime.class)), userId);
        return keys.stream().findFirst();
    }

    public SigningKey register(UUID userId, String encodedPublicKey) {
        byte[] publicKey = decodePublicKey(encodedPublicKey);
        Optional<SigningKey> existing = findActive(userId);
        if (existing.isPresent()) {
            if (MessageDigest.isEqual(existing.get().publicKey(), publicKey)) return existing.get();
            throw new ApiException(HttpStatus.CONFLICT,
                    "This account already has a different active signing key.");
        }

        UUID keyId = UuidV5.url("instichain:user-signing-key:"
                + Base64.getEncoder().encodeToString(publicKey));
        try {
            jdbc.update("""
                    INSERT INTO user_signing_keys(id, user_id, public_key)
                    VALUES (?, ?, ?)
                    """, keyId, userId, publicKey);
        } catch (DuplicateKeyException duplicate) {
            Optional<SigningKey> concurrent = findActive(userId);
            if (concurrent.isPresent()
                    && MessageDigest.isEqual(concurrent.get().publicKey(), publicKey)) {
                return concurrent.get();
            }
            throw new ApiException(HttpStatus.CONFLICT,
                    "This signing key is already registered to another account.");
        }
        return findActive(userId).orElseThrow();
    }

    public Map<String, Object> response(SigningKey key) {
        String encoded = Base64.getEncoder().encodeToString(key.publicKey());
        return Map.of(
                "registered", true,
                "key_id", key.id().toString(),
                "algorithm", "Ed25519",
                "public_key", encoded,
                "fingerprint", fingerprint(key.publicKey()));
    }

    private byte[] decodePublicKey(String encodedPublicKey) {
        if (encodedPublicKey == null || encodedPublicKey.length() != 44) {
            throw invalidPublicKey();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedPublicKey);
            if (decoded.length != 32 || java.util.Arrays.equals(decoded, new byte[32])
                    || !Base64.getEncoder().encodeToString(decoded).equals(encodedPublicKey)) {
                throw invalidPublicKey();
            }
            return decoded;
        } catch (IllegalArgumentException invalidBase64) {
            throw invalidPublicKey();
        }
    }

    private String fingerprint(byte[] publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            String hex = HexFormat.of().withUpperCase().formatHex(digest);
            return String.join(":", hex.replaceAll("(..)(?!$)", "$1 ").split(" "));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ApiException invalidPublicKey() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Provide a valid 32-byte Ed25519 public key.");
    }

    public record SigningKey(UUID id, UUID userId, byte[] publicKey, OffsetDateTime createdAt) {}
}

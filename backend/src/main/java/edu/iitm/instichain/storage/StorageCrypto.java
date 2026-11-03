package edu.iitm.instichain.storage;

import edu.iitm.instichain.config.InstiChainProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class StorageCrypto {
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public StorageCrypto(InstiChainProperties properties, Environment environment) {
        String configured = properties.proofEncryptionKey();
        if (configured == null || configured.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("INSTICHAIN_PROOF_ENCRYPTION_KEY is required in production");
            }
            key = null;
            return;
        }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("Invalid proof encryption key"); }
        if (decoded.length != 32) throw new IllegalStateException("Proof encryption key must be 32 bytes");
        key = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public byte[] encrypt(String secret) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[1 + IV_LENGTH + encrypted.length];
            result[0] = 1;
            System.arraycopy(iv, 0, result, 1, IV_LENGTH);
            System.arraycopy(encrypted, 0, result, 1 + IV_LENGTH, encrypted.length);
            return result;
        } catch (Exception error) { throw new IllegalStateException("Could not encrypt storage credential"); }
    }

    public String decrypt(byte[] stored) {
        requireKey();
        if (stored == null || stored.length < 1 + IV_LENGTH + 16 || stored[0] != 1) {
            throw new IllegalArgumentException("Invalid encrypted storage credential");
        }
        try {
            byte[] iv = Arrays.copyOfRange(stored, 1, 1 + IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(stored, 1 + IV_LENGTH,
                    stored.length - 1 - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid encrypted storage credential"); }
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("Configure INSTICHAIN_PROOF_ENCRYPTION_KEY");
    }
}

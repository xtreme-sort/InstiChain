package edu.iitm.instichain.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.iitm.instichain.config.InstiChainProperties;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StorageFoundationTest {
    private static InstiChainProperties properties(String key) {
        return new InstiChainProperties("InstiChain", "http://127.0.0.1:5173", 15, 20, 30,
                null, null, null, "", "", key, Path.of(".private/proofs"), 1024,
                "", "", "");
    }

    @Test
    void encryptsRefreshTokensWithRandomIvAndRejectsTampering() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        StorageCrypto crypto = new StorageCrypto(properties(key), new MockEnvironment());
        byte[] first = crypto.encrypt("refresh-secret");
        byte[] second = crypto.encrypt("refresh-secret");
        assertFalse(java.util.Arrays.equals(first, second));
        assertEquals("refresh-secret", crypto.decrypt(first));
        first[first.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(first));
    }

    @Test
    void productionRequiresAConfiguredKey() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new StorageCrypto(properties(""),
                production));
    }

    @Test
    void secretsAndProviderPathsAreRedactedFromRepresentations() {
        UUID id = UUID.randomUUID();
        StorageRepository.Connection connection = new StorageRepository.Connection(id, id, "google",
                "sensitive".getBytes(), "drive.file", "connected", OffsetDateTime.now());
        StorageRepository.Artifact artifact = new StorageRepository.Artifact(id, id, id, "google",
                "private-file-id", "proof.pdf", "application/pdf", 10, "a".repeat(64),
                "/private/archive/path", "unchecked", null);
        assertFalse(connection.toString().contains("sensitive"));
        assertFalse(artifact.toString().contains("private-file-id"));
        assertFalse(artifact.toString().contains("/private/archive/path"));
    }

    @Test
    void providerAdapterCanBeFakedWithoutNetwork() throws Exception {
        StorageAdapter fake = new StorageAdapter() {
            public String provider() { return "test"; }
            public FileMetadata metadata(String token, String id) {
                return new FileMetadata(id, "proof.pdf", "application/pdf", 3);
            }
            public java.io.InputStream download(String token, String id) {
                return new ByteArrayInputStream(new byte[]{1, 2, 3});
            }
        };
        assertEquals(3, fake.metadata("token", "file").size());
        assertArrayEquals(new byte[]{1, 2, 3}, fake.download("token", "file").readAllBytes());
    }
}

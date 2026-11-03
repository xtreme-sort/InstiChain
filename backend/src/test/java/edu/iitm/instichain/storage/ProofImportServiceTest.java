package edu.iitm.instichain.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;

class ProofImportServiceTest {
    @TempDir Path temp;

    private InstiChainProperties properties(long max) {
        return new InstiChainProperties("InstiChain", "http://127.0.0.1:5173", 15, 20, 30,
                null, null, null, "", "", "", temp.resolve("proofs"), max, "", "", "");
    }

    private ProofImportService service(StorageAdapter adapter, long max) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("queryForList")) return List.of(UUID.randomUUID());
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        StorageRepository storage = mock(StorageRepository.class);
        GoogleConnectionService connection = mock(GoogleConnectionService.class);
        UUID owner = OWNER;
        when(storage.connection(owner, "google")).thenReturn(new StorageRepository.Connection(
                UUID.randomUUID(), owner, "google", new byte[]{1},
                GoogleOAuthGateway.DRIVE_FILE_SCOPE, "connected", OffsetDateTime.now()));
        when(connection.accessToken(owner)).thenReturn("access");
        return new ProofImportService(jdbc, storage, connection, adapter, properties(max));
    }

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void hashesExactStreamedBytesAndArchivesThem() throws Exception {
        StorageAdapter adapter = fake("application/pdf", 3, new byte[]{'a', 'b', 'c'});
        ProofImportService.Proof proof = service(adapter, 100).importFile(OWNER, "file123");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                proof.sha256Digest());
        try (var paths = Files.list(temp.resolve("proofs"))) {
            Path archived = paths.findFirst().orElseThrow();
            assertEquals("abc", Files.readString(archived));
        }
    }

    @Test
    void rejectsDisallowedTypeAndOversizedStream() throws Exception {
        assertThrows(ApiException.class, () -> service(fake("text/plain", 3, new byte[]{1, 2, 3}), 100)
                .importFile(OWNER, "file123"));
        assertThrows(ApiException.class, () -> service(fake("application/pdf", 3,
                new byte[]{1, 2, 3, 4}), 100).importFile(OWNER, "file123"));
        try (var paths = Files.exists(temp.resolve("proofs")) ? Files.list(temp.resolve("proofs"))
                : java.util.stream.Stream.<Path>empty()) {
            assertTrue(paths.findAny().isEmpty());
        }
    }

    private static StorageAdapter fake(String mime, long declaredSize, byte[] bytes) {
        return new StorageAdapter() {
            public String provider() { return "google"; }
            public FileMetadata metadata(String token, String id) {
                return new FileMetadata(id, "proof.pdf", mime, declaredSize);
            }
            public InputStream download(String token, String id) {
                return new ByteArrayInputStream(bytes);
            }
        };
    }
}

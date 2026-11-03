package edu.iitm.instichain.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProofHealthServiceTest {
    @TempDir Path temp;

    @Test
    void digestIsExactAndBounded() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ProofHealthService.digest(new ByteArrayInputStream("abc".getBytes()), 3));
        assertThrows(IllegalArgumentException.class,
                () -> ProofHealthService.digest(new ByteArrayInputStream("abcd".getBytes()), 3));
    }

    @Test
    void providerOutageDoesNotInvalidateMatchingArchive() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Path archive = temp.resolve("proof.bin");
        Files.writeString(archive, "abc");
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StorageRepository storage = mock(StorageRepository.class);
        GoogleConnectionService connection = mock(GoogleConnectionService.class);
        StorageAdapter adapter = new StorageAdapter() {
            public String provider() { return "google"; }
            public FileMetadata metadata(String token, String id) { throw new UnsupportedOperationException(); }
            public InputStream download(String token, String id) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider unavailable");
            }
        };
        when(storage.ownedArtifact(owner, artifactId)).thenReturn(new StorageRepository.Artifact(
                artifactId, owner, UUID.randomUUID(), "google", "file", "proof.pdf", "application/pdf",
                3, digest, archive.toString(), "unchecked", null));
        when(storage.connection(owner, "google")).thenReturn(new StorageRepository.Connection(
                UUID.randomUUID(), owner, "google", new byte[]{1}, "drive.file",
                "connected", OffsetDateTime.now()));
        when(connection.accessToken(owner)).thenReturn("access");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(artifactId))).thenReturn(List.of(
                new ProofHealthService.Health("matching", "temporarily_unavailable", null,
                        "temporarily_unavailable")));
        InstiChainProperties properties = new InstiChainProperties("InstiChain",
                "http://127.0.0.1:5173", 15, 20, 30, null, null, null, "", "", "",
                temp, 100, "", "", "");
        ProofHealthService.Health health = new ProofHealthService(jdbc, storage, connection,
                adapter, properties).check(owner, artifactId);
        assertEquals("matching", health.archiveStatus());
        assertEquals("temporarily_unavailable", health.providerStatus());
        verify(jdbc).update(anyString(), eq("matching"), eq("temporarily_unavailable"),
                eq("temporarily_unavailable"), eq(artifactId), eq(owner));
    }
}

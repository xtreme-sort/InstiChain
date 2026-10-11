package edu.iitm.instichain.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SigningKeyRequestTest {
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void readsTheDocumentedSnakeCasePublicKey() throws Exception {
        String publicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

        SigningKeyController.RegisterRequest request = json.readValue(
                "{\"public_key\":\"" + publicKey + "\"}",
                SigningKeyController.RegisterRequest.class);

        assertEquals(publicKey, request.publicKey());
    }

    @Test
    void readsSigningKeyDuringFirstRegistration() throws Exception {
        String publicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

        EmailAuthController.ConfirmRequest request = json.readValue("""
                {"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                 "display_name":"Test User","public_key":"%s"}
                """.formatted(publicKey), EmailAuthController.ConfirmRequest.class);

        assertEquals("Test User", request.displayName());
        assertEquals(publicKey, request.publicKey());
    }

    @Test
    void serializesTheRegisteredKeyResponse() throws Exception {
        byte[] publicKey = new byte[32];
        java.util.Arrays.fill(publicKey, (byte) 1);
        SigningKeyService service = new SigningKeyService(null);

        String response = json.writeValueAsString(service.response(new SigningKeyService.SigningKey(
                UUID.randomUUID(), UUID.randomUUID(), publicKey, OffsetDateTime.now())));

        assertTrue(response.contains("\"registered\":true"));
        assertTrue(response.contains("\"public_key\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\""));
        assertTrue(response.contains("\"fingerprint\":"));
    }
}

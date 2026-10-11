package edu.iitm.instichain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.iitm.instichain.auth.InstituteEmail;
import edu.iitm.instichain.auth.MailService;
import edu.iitm.instichain.config.InstiChainProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class IntegrationPreflightService {
    private static final Pattern DRIVE_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private final MailService mail;
    private final InstiChainProperties properties;
    private final ObjectMapper json;

    public IntegrationPreflightService(MailService mail, InstiChainProperties properties,
                                       ObjectMapper json) {
        this.mail = mail;
        this.properties = properties;
        this.json = json;
    }

    public String smtp(String recipient) throws Exception {
        if (recipient == null) {
            mail.checkConnection();
            return "SMTP connection and configured authentication succeeded. No message was sent.";
        }
        mail.sendDeliveryCheck(InstituteEmail.normalize(recipient));
        return "SMTP accepted the test message. Confirm receipt; acceptance alone is not delivery proof.";
    }

    public String drive() throws Exception {
        String token = properties.driveTestAccessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "Set INSTICHAIN_DRIVE_TEST_ACCESS_TOKEN with drive.file authorization.");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JsonNode account = get(client,
                "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", token,
                "Drive account check");
        if (account.path("user").path("permissionId").asText().isBlank()) {
            throw new IllegalArgumentException("Drive did not identify the authorized account.");
        }
        String fileId = properties.driveTestFileId();
        if (fileId == null || fileId.isBlank()) {
            return "Drive account access confirmed. File access was not checked.";
        }
        if (!DRIVE_ID.matcher(fileId).matches()) {
            throw new IllegalArgumentException("Invalid Drive test file ID.");
        }
        String encoded = URLEncoder.encode(fileId, StandardCharsets.UTF_8);
        JsonNode file = get(client, "https://www.googleapis.com/drive/v3/files/" + encoded
                + "?fields=id,trashed,capabilities(canDownload)&supportsAllDrives=true", token,
                "Drive file check");
        if (!fileId.equals(file.path("id").asText()) || file.path("trashed").asBoolean()
                || !file.path("capabilities").path("canDownload").asBoolean()) {
            throw new IllegalArgumentException("The Drive test file is unavailable or cannot be downloaded.");
        }
        return "Drive account and selected-file metadata access confirmed. No file was modified.";
    }

    private JsonNode get(HttpClient client, String url, String token, String label) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException(label + " failed (HTTP " + response.statusCode()
                    + "); check token expiry, scope, and API access.");
        }
        return json.readTree(response.body());
    }
}

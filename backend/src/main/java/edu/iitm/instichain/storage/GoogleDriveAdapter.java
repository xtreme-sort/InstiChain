package edu.iitm.instichain.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.iitm.instichain.common.ApiException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GoogleDriveAdapter implements StorageAdapter {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GoogleDriveAdapter(ObjectMapper json) { this.json = json; }

    @Override public String provider() { return "google"; }

    @Override public FileMetadata metadata(String accessToken, String fileId) {
        URI uri = URI.create("https://www.googleapis.com/drive/v3/files/" + checkedId(fileId)
                + "?fields=id,name,mimeType,size");
        HttpResponse<String> response = send(request(uri, accessToken), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404 || response.statusCode() == 403) throw inaccessible();
        if (response.statusCode() != 200) throw unavailable();
        try {
            JsonNode file = json.readTree(response.body());
            return new FileMetadata(file.path("id").asText(""), file.path("name").asText(""),
                    file.path("mimeType").asText(""), file.path("size").asLong(-1));
        } catch (Exception error) { throw unavailable(); }
    }

    @Override public InputStream download(String accessToken, String fileId) {
        URI uri = URI.create("https://www.googleapis.com/drive/v3/files/" + checkedId(fileId) + "?alt=media");
        HttpResponse<InputStream> response = send(request(uri, accessToken), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try { response.body().close(); } catch (Exception ignored) { }
            if (response.statusCode() == 404 || response.statusCode() == 403) throw inaccessible();
            throw unavailable();
        }
        return response.body();
    }

    private static HttpRequest request(URI uri, String token) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).GET().build();
    }

    private static String checkedId(String id) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,200}$")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Drive file identifier.");
        }
        return id;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try { return http.send(request, handler); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception error) { throw unavailable(); }
    }

    private static ApiException inaccessible() {
        return new ApiException(HttpStatus.NOT_FOUND, "The file is not accessible to this app.");
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive is temporarily unavailable.");
    }
}

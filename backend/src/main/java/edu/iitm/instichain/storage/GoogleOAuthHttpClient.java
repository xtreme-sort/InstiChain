package edu.iitm.instichain.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GoogleOAuthHttpClient implements GoogleOAuthGateway {
    private static final URI TOKEN = URI.create("https://oauth2.googleapis.com/token");
    private static final URI REVOKE = URI.create("https://oauth2.googleapis.com/revoke");
    private final InstiChainProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GoogleOAuthHttpClient(InstiChainProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override public URI authorizationUrl(String state) {
        configured();
        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", properties.googleClientId())
                .queryParam("redirect_uri", properties.googleRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", DRIVE_FILE_SCOPE)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state).build().encode().toUri();
    }

    @Override public Tokens exchange(String code) {
        configured();
        return tokens(Map.of("code", code, "client_id", properties.googleClientId(),
                "client_secret", properties.googleClientSecret(), "redirect_uri", properties.googleRedirectUri(),
                "grant_type", "authorization_code"));
    }

    @Override public Tokens refresh(String refreshToken) {
        configured();
        return tokens(Map.of("refresh_token", refreshToken, "client_id", properties.googleClientId(),
                "client_secret", properties.googleClientSecret(), "grant_type", "refresh_token"));
    }

    @Override public void revoke(String refreshToken) {
        HttpRequest request = HttpRequest.newBuilder(REVOKE)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", refreshToken)))).build();
        send(request);
    }

    private Tokens tokens(Map<String, String> fields) {
        HttpRequest request = HttpRequest.newBuilder(TOKEN)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) throw unavailable();
        try {
            JsonNode body = json.readTree(response.body());
            String access = body.path("access_token").asText("");
            if (access.isBlank()) throw unavailable();
            return new Tokens(access, body.path("refresh_token").asText(""),
                    body.path("scope").asText(""));
        } catch (IOException error) { throw unavailable(); }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try { return http.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (IOException error) { throw unavailable(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
    }

    private void configured() {
        if (properties.googleClientId() == null || properties.googleClientId().isBlank()
                || properties.googleClientSecret() == null || properties.googleClientSecret().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive is not configured.");
        }
        URI redirect;
        try { redirect = URI.create(properties.googleRedirectUri()); }
        catch (RuntimeException error) { throw new IllegalStateException("Invalid Google redirect URI"); }
        if (redirect.getHost() == null || redirect.getFragment() != null || redirect.getQuery() != null
                || !"/api/storage/google/callback".equals(redirect.getPath())
                || !("https".equals(redirect.getScheme())
                  || ("http".equals(redirect.getScheme()) && "127.0.0.1".equals(redirect.getHost())))) {
            throw new IllegalStateException("Google redirect URI must be the configured HTTPS callback (or loopback development callback)");
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive is temporarily unavailable.");
    }
}

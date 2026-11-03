package edu.iitm.instichain.storage;

import java.net.URI;

public interface GoogleOAuthGateway {
    String DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";

    URI authorizationUrl(String state);
    Tokens exchange(String code);
    Tokens refresh(String refreshToken);
    void revoke(String refreshToken);

    record Tokens(String accessToken, String refreshToken, String scope) {
        @Override public String toString() { return "Tokens[REDACTED]"; }
    }
}

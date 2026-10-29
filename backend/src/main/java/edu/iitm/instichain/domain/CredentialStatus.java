package edu.iitm.instichain.domain;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

@Service
public class CredentialStatus {
    public String current(OffsetDateTime revokedAt, boolean superseded) {
        if (revokedAt != null) return "revoked";
        return superseded ? "superseded" : "active";
    }
}

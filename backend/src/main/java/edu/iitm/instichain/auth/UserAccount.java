package edu.iitm.instichain.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserAccount(UUID id, String email, String displayName, OffsetDateTime emailVerifiedAt) {}

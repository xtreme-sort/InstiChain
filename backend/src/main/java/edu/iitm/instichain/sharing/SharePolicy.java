package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.ApiException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class SharePolicy {
    private SharePolicy() {}

    public static void validate(List<UUID> credentialIds, OffsetDateTime expiresAt,
                                OffsetDateTime now) {
        if (credentialIds == null || credentialIds.isEmpty() || credentialIds.size() > 50
                || credentialIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(credentialIds).size() != credentialIds.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Select between 1 and 50 distinct credentials.");
        }
        if (expiresAt == null || expiresAt.isBefore(now.plusHours(1))
                || expiresAt.isAfter(now.plusDays(30))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Share expiry must be between 1 hour and 30 days from now.");
        }
    }
}

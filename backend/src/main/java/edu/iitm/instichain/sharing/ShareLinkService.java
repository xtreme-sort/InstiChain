package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareLinkService {
    private final JdbcTemplate jdbc;
    private final ShareRepository repository;

    public ShareLinkService(JdbcTemplate jdbc, ShareRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    public record Created(UUID id, String token, OffsetDateTime expiresAt,
                          List<UUID> credentialIds) {
        @Override public String toString() {
            return "Created[id=" + id + ", token=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }
    public record Summary(UUID id, String label, OffsetDateTime createdAt,
                          OffsetDateTime expiresAt, OffsetDateTime revokedAt,
                          List<UUID> credentialIds) {}

    @Transactional
    public Created create(UUID ownerId, UUID requestId, String label,
                          OffsetDateTime expiresAt, List<UUID> credentialIds) {
        SharePolicy.validate(credentialIds, expiresAt, OffsetDateTime.now(ZoneOffset.UTC));
        repository.requireOwned(ownerId, credentialIds);
        repository.requireActive(ownerId, credentialIds);
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM share_links WHERE owner_id = ?
                    AND created_at > clock_timestamp() - interval '1 day'
                """, Integer.class, ownerId);
        if (recent != null && recent >= 20) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily share limit reached.");
        }
        String safeLabel = label == null || label.isBlank() ? null : label.strip();
        if (safeLabel != null && (safeLabel.length() > 120 || safeLabel.chars().anyMatch(Character::isISOControl))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid share label.");
        }
        ShareToken token = ShareToken.issue();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO share_links(id, owner_id, token_hash, client_request_id, label, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, ownerId, token.hash(), requestId, safeLabel, expiresAt);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This share request was already used. Create a new request to receive a new link.");
        }
        for (UUID credentialId : credentialIds) {
            jdbc.update("""
                    INSERT INTO share_link_credentials(share_id, owner_id, credential_id)
                    VALUES (?, ?, ?)
                    """, id, ownerId, credentialId);
        }
        return new Created(id, token.value(), expiresAt, List.copyOf(credentialIds));
    }

    public List<Summary> list(UUID ownerId) {
        List<Summary> links = jdbc.query("""
                SELECT id, label, created_at, expires_at, revoked_at
                FROM share_links WHERE owner_id = ? ORDER BY created_at DESC, id
                """, (row, number) -> new Summary(row.getObject("id", UUID.class),
                row.getString("label"), row.getObject("created_at", OffsetDateTime.class),
                row.getObject("expires_at", OffsetDateTime.class),
                row.getObject("revoked_at", OffsetDateTime.class), List.of()), ownerId);
        return links.stream().map(link -> new Summary(link.id(), link.label(), link.createdAt(),
                link.expiresAt(), link.revokedAt(), jdbc.queryForList("""
                SELECT credential_id FROM share_link_credentials
                WHERE share_id = ? AND owner_id = ? ORDER BY credential_id
                """, UUID.class, link.id(), ownerId))).toList();
    }

    @Transactional
    public void revoke(UUID ownerId, UUID shareId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM share_links WHERE id = ? AND owner_id = ?)
                """, Boolean.class, shareId, ownerId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException(HttpStatus.NOT_FOUND, "Share not found.");
        jdbc.update("""
                UPDATE share_links SET revoked_at = clock_timestamp(), revoked_by_id = ?
                WHERE id = ? AND owner_id = ? AND revoked_at IS NULL
                """, ownerId, shareId, ownerId);
    }
}

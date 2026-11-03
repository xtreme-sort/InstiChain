package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.ApiException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShareRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ShareRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireOwned(UUID ownerId, List<UUID> credentialIds) {
        List<UUID> found = jdbc.queryForList("""
                SELECT id FROM credentials
                WHERE recipient_id = :owner AND id IN (:ids)
                """, new MapSqlParameterSource().addValue("owner", ownerId)
                .addValue("ids", credentialIds), UUID.class);
        if (!Set.copyOf(found).equals(Set.copyOf(credentialIds))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Every selected credential must belong to your account.");
        }
    }

    public void requireActive(UUID ownerId, List<UUID> credentialIds) {
        List<UUID> active = jdbc.queryForList("""
                SELECT cr.id FROM credentials cr
                WHERE cr.recipient_id = :owner AND cr.id IN (:ids)
                  AND cr.revoked_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM credentials next WHERE next.supersedes_id = cr.id)
                  AND NOT EXISTS(SELECT 1 FROM fabric_transactions successor
                      WHERE successor.event_type = 'ACHIEVEMENT_ISSUED'
                        AND successor.payload->'superseded_credential_ids'
                            @> jsonb_build_array(cr.id::text))
                """, new MapSqlParameterSource().addValue("owner", ownerId)
                .addValue("ids", credentialIds), UUID.class);
        if (!Set.copyOf(active).equals(Set.copyOf(credentialIds))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only active credentials can be selected for a new share.");
        }
    }
}

package edu.iitm.instichain.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.common.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ShareFoundationTest {
    @Test
    void tokensAreUniqueHashedAndRedacted() {
        ShareToken first = ShareToken.issue();
        ShareToken second = ShareToken.issue();
        assertEquals(43, first.value().length());
        assertEquals(64, first.hash().length());
        assertNotEquals(first.value(), first.hash());
        assertNotEquals(first.hash(), second.hash());
        assertFalse(first.toString().contains(first.value()));
    }

    @Test
    void policyRejectsDuplicatesAndInvalidExpiry() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        SharePolicy.validate(List.of(id), now.plusDays(1), now);
        assertThrows(ApiException.class, () -> SharePolicy.validate(List.of(id, id), now.plusDays(1), now));
        assertThrows(ApiException.class, () -> SharePolicy.validate(List.of(id), now.plusMinutes(30), now));
        assertThrows(ApiException.class, () -> SharePolicy.validate(List.of(id), now.plusDays(31), now));
    }

    @Test
    void ownershipCheckRejectsOtherStudentsCredentials() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID owner = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(UUID.class))).thenReturn(List.of());
        assertThrows(ApiException.class, () -> new ShareRepository(jdbc)
                .requireOwned(owner, List.of(credential)));
    }
}

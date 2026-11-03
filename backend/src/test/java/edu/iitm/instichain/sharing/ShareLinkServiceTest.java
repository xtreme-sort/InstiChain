package edu.iitm.instichain.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class ShareLinkServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShareRepository repository = mock(ShareRepository.class);
    private final ShareLinkService service = new ShareLinkService(jdbc, repository);
    private final UUID owner = UUID.randomUUID();
    private final UUID credential = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @Test
    void createsWithHashedTokenAndReturnsRawTokenOnlyOnce() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(owner))).thenReturn(0);
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        ShareLinkService.Created created = service.create(owner, requestId, "Portfolio", expiry,
                List.of(credential));
        assertEquals(List.of(credential), created.credentialIds());
        assertEquals(43, created.token().length());
        assertFalse(created.toString().contains(created.token()));
        verify(jdbc).update(anyString(), eq(created.id()), eq(owner),
                eq(Hashing.sha256(created.token())), eq(requestId), eq("Portfolio"), eq(expiry));
        verify(jdbc).update(anyString(), eq(created.id()), eq(owner), eq(credential));
        verify(repository).requireOwned(owner, List.of(credential));
        verify(repository).requireActive(owner, List.of(credential));
    }

    @Test
    void refusesDuplicateSelectionAndInvalidExpiryBeforeDatabaseAccess() {
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        assertThrows(ApiException.class, () -> service.create(owner, requestId, null, expiry,
                List.of(credential, credential)));
        assertThrows(ApiException.class, () -> service.create(owner, requestId, null,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), List.of(credential)));
        assertThrows(ApiException.class, () -> service.create(owner, requestId, null,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(31), List.of(credential)));
        verify(repository, never()).requireOwned(any(), any());
    }

    @Test
    void replayCannotReceiveTheOriginalToken() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(owner))).thenReturn(0);
        when(jdbc.update(anyString(), any(), eq(owner), any(), eq(requestId), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        ApiException error = assertThrows(ApiException.class, () -> service.create(owner,
                requestId, null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                List.of(credential)));
        assertFalse(error.getMessage().contains("duplicate"));
    }

    @Test
    void enforcesDailyLimitAndOwnerScopedRevocation() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(owner))).thenReturn(20);
        assertThrows(ApiException.class, () -> service.create(owner, requestId, null,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), List.of(credential)));
        UUID shareId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(shareId), eq(owner)))
                .thenReturn(true);
        service.revoke(owner, shareId);
        verify(jdbc).update(anyString(), eq(owner), eq(shareId), eq(owner));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(shareId), eq(owner)))
                .thenReturn(false);
        assertThrows(ApiException.class, () -> service.revoke(owner, shareId));
    }

    @Test
    void listReturnsMetadataWithoutBearerToken() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(owner)))
                .thenReturn(List.of());
        assertTrue(service.list(owner).isEmpty());
    }
}

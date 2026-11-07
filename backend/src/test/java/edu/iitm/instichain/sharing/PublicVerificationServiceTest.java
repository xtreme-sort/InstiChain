package edu.iitm.instichain.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import edu.iitm.instichain.domain.CredentialVerificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PublicVerificationServiceTest {
    private static final String TOKEN = "a".repeat(43);
    private final PublicShareRepository shares = mock(PublicShareRepository.class);
    private final CredentialVerificationService verifier = mock(CredentialVerificationService.class);
    private final PublicVerificationService service = new PublicVerificationService(shares, verifier);
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    private final UUID shareId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();

    private void sharedCredentialVerifiesAs(String authenticity, String authorization,
                                            String lifecycle, String ledger, String proof) {
        when(shares.activeShare(Hashing.sha256(TOKEN))).thenReturn(Optional.of(
                new PublicShareRepository.ActiveShare(shareId, "Student Name", now.plusDays(1))));
        when(shares.credentials(shareId)).thenReturn(List.of(new PublicShareRepository.SharedCredential(
                credentialId, "First place", "Hackathon", "Coding Club", "Advisor Name",
                now.minusDays(3), "fabric-tx-1")));
        when(shares.now()).thenReturn(now);
        CredentialVerificationService.Subject subject = new CredentialVerificationService.Subject(
                credentialId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), now.minusDays(3), null, null, false);
        when(verifier.subject(credentialId)).thenReturn(subject);
        when(verifier.verify(subject)).thenReturn(new CredentialVerificationService.Verification(
                credentialId, authenticity, ledger, authorization, lifecycle, proof, null));
    }

    private void assertUnavailable(String token) {
        ApiException error = assertThrows(ApiException.class, () -> service.verify(token));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals(PublicVerificationService.UNAVAILABLE, error.getMessage());
    }

    @Test
    void malformedTokenIsRejectedWithoutTouchingTheDatabase() {
        for (String token : new String[]{null, "", "short", "a".repeat(44), "a".repeat(42) + "!"}) {
            assertUnavailable(token);
        }
        verify(shares, never()).activeShare(any());
    }

    @Test
    void unknownExpiredAndRevokedLinksAreIndistinguishable() {
        // The repository filters expiry and revocation in SQL, so all three arrive as empty.
        when(shares.activeShare(any())).thenReturn(Optional.empty());
        assertUnavailable(TOKEN);
    }

    @Test
    void activeCredentialWithValidChecksIsValid() {
        sharedCredentialVerifiesAs("valid", "valid", "active", "confirmed", "matching");
        PublicVerificationService.PublicCredential result = service.verify(TOKEN).credentials().getFirst();
        assertEquals("valid", result.verdict());
        assertEquals("verified", result.proofStatus());
    }

    @Test
    void revocationAfterSharingIsReportedLive() {
        sharedCredentialVerifiesAs("valid", "valid", "revoked", "confirmed", "matching");
        assertEquals("revoked", service.verify(TOKEN).credentials().getFirst().verdict());
    }

    @Test
    void tamperedSignatureOrAuthorityIsInvalidEvenIfActive() {
        sharedCredentialVerifiesAs("invalid", "valid", "active", "mismatch", "matching");
        assertEquals("invalid", service.verify(TOKEN).credentials().getFirst().verdict());
        sharedCredentialVerifiesAs("valid", "invalid", "active", "confirmed", "matching");
        assertEquals("invalid", service.verify(TOKEN).credentials().getFirst().verdict());
    }

    @Test
    void fabricOutageIsUnknownNeverValid() {
        sharedCredentialVerifiesAs("unknown", "valid", "active", "unavailable", "matching");
        PublicVerificationService.PublicCredential result = service.verify(TOKEN).credentials().getFirst();
        assertEquals("unknown", result.verdict());
        assertEquals("unavailable", result.ledgerStatus());
    }

    @Test
    void onlyTheLinksOwnSelectionIsVerified() {
        sharedCredentialVerifiesAs("valid", "valid", "active", "confirmed", "matching");
        assertEquals(1, service.verify(TOKEN).credentials().size());
        verify(shares).credentials(shareId);
    }

    @Test
    void responseExposesNoEmailsOrProviderDetail() throws Exception {
        sharedCredentialVerifiesAs("valid", "valid", "active", "confirmed", "provider_missing");
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.verify(TOKEN));
        assertFalse(body.contains("@"), body);
        assertFalse(body.contains("provider"), body);
        assertEquals("unavailable", PublicVerificationService.proofStatus("provider_changed"));
        assertEquals("digest_only", PublicVerificationService.proofStatus("digest_only"));
    }
}

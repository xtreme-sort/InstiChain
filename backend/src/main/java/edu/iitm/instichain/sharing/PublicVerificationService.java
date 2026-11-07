package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.common.Hashing;
import edu.iitm.instichain.domain.CredentialVerificationService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Live verification for a student-issued share link. Every call re-runs the shared
 * verifier, so a revocation recorded after the link was created is reported immediately.
 */
@Service
public class PublicVerificationService {
    static final String UNAVAILABLE = "This verification link is unavailable.";
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private final PublicShareRepository shares;
    private final CredentialVerificationService verifier;

    public PublicVerificationService(PublicShareRepository shares,
                                     CredentialVerificationService verifier) {
        this.shares = shares;
        this.verifier = verifier;
    }

    public record Result(OffsetDateTime checkedAt, String recipient, OffsetDateTime linkExpiresAt,
                         List<PublicCredential> credentials) {}
    public record PublicCredential(UUID credentialId, String award, String competition,
                                   String club, String issuer, OffsetDateTime issuedAt,
                                   String fabricTransactionId, String verdict,
                                   String authenticity, String ledgerStatus,
                                   String historicalAuthorization, String lifecycleStatus,
                                   String proofStatus) {}

    public Result verify(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw unavailable();
        PublicShareRepository.ActiveShare share = shares.activeShare(Hashing.sha256(token))
                .orElseThrow(PublicVerificationService::unavailable);
        List<PublicCredential> credentials = shares.credentials(share.id()).stream()
                .map(this::check).toList();
        return new Result(shares.now(), share.recipientName(), share.expiresAt(), credentials);
    }

    private PublicCredential check(PublicShareRepository.SharedCredential credential) {
        CredentialVerificationService.Verification result =
                verifier.verify(verifier.subject(credential.id()));
        return new PublicCredential(credential.id(), credential.award(), credential.competition(),
                credential.club(), credential.issuer(), credential.issuedAt(),
                credential.fabricTransactionId(), verdict(result), result.authenticity(),
                result.ledgerStatus(), result.historicalAuthorization(), result.lifecycleStatus(),
                proofStatus(result.proofAvailability()));
    }

    /** A single headline that never upgrades an unknown check to valid. */
    static String verdict(CredentialVerificationService.Verification result) {
        if ("invalid".equals(result.authenticity())
                || "invalid".equals(result.historicalAuthorization())) return "invalid";
        if ("revoked".equals(result.lifecycleStatus())) return "revoked";
        if ("superseded".equals(result.lifecycleStatus())) return "superseded";
        if ("valid".equals(result.authenticity())
                && "valid".equals(result.historicalAuthorization())
                && "active".equals(result.lifecycleStatus())) return "valid";
        return "unknown";
    }

    /** Reduces internal storage states so no provider detail reaches the public. */
    static String proofStatus(String availability) {
        if ("matching".equals(availability)) return "verified";
        if ("digest_only".equals(availability)) return "digest_only";
        if ("unchecked".equals(availability)) return "unchecked";
        return "unavailable";
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, UNAVAILABLE);
    }
}

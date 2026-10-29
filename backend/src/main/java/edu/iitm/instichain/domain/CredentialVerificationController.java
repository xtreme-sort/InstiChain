package edu.iitm.instichain.domain;

import edu.iitm.instichain.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CredentialVerificationController {
    private final AccessService access;
    private final CredentialVerificationService verification;

    public CredentialVerificationController(AccessService access, CredentialVerificationService verification) {
        this.access = access;
        this.verification = verification;
    }

    @GetMapping("/credentials/{credentialId}/verification")
    CredentialVerificationService.Verification studentVerification(@PathVariable UUID credentialId,
                                                                   HttpServletRequest request) {
        UUID studentId = access.user(request).id();
        CredentialVerificationService.Subject subject = verification.subject(credentialId);
        if (!studentId.equals(subject.recipientId())) throw notFound();
        return verification.verify(subject);
    }

    @GetMapping("/credentials/{credentialId}/history")
    List<CredentialVerificationService.HistoryItem> studentHistory(@PathVariable UUID credentialId,
                                                                    HttpServletRequest request) {
        UUID studentId = access.user(request).id();
        CredentialVerificationService.Subject subject = verification.subject(credentialId);
        if (!studentId.equals(subject.recipientId())) throw notFound();
        return verification.history(subject);
    }

    @GetMapping("/advisor/clubs/{clubId}/credentials/{credentialId}/verification")
    CredentialVerificationService.Verification advisorVerification(@PathVariable UUID clubId,
                                                                   @PathVariable UUID credentialId,
                                                                   HttpServletRequest request) {
        access.club(request, clubId, Set.of("faculty_advisor"));
        CredentialVerificationService.Subject subject = verification.subject(credentialId);
        if (!clubId.equals(subject.clubId())) throw notFound();
        return verification.verify(subject);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Credential not found.");
    }
}

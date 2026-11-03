package edu.iitm.instichain.storage;

import edu.iitm.instichain.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProofHealthController {
    private final SessionService sessions;
    private final ProofHealthService proofs;

    public ProofHealthController(SessionService sessions, ProofHealthService proofs) {
        this.sessions = sessions;
        this.proofs = proofs;
    }

    @GetMapping("/proofs/{artifactId}/health")
    ProofHealthService.Health cached(@PathVariable UUID artifactId, HttpServletRequest request) {
        return proofs.cached(sessions.requireUser(request).id(), artifactId);
    }

    @PostMapping("/proofs/{artifactId}/check")
    ProofHealthService.Health check(@PathVariable UUID artifactId, HttpServletRequest request) {
        UUID ownerId = sessions.requireUser(request).id();
        sessions.requireSameOrigin(request);
        return proofs.check(ownerId, artifactId);
    }

    @GetMapping("/credentials/{credentialId}/proof-health")
    ProofHealthService.Health credential(@PathVariable UUID credentialId, HttpServletRequest request) {
        return proofs.forCredential(sessions.requireUser(request).id(), credentialId);
    }
}

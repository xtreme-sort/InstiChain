package edu.iitm.instichain.storage;

import edu.iitm.instichain.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proofs")
public class ProofImportController {
    private final SessionService sessions;
    private final ProofImportService proofs;

    public ProofImportController(SessionService sessions, ProofImportService proofs) {
        this.sessions = sessions;
        this.proofs = proofs;
    }

    record ImportRequest(@NotBlank @Size(max = 200) String fileId) {}

    @GetMapping
    List<ProofImportService.Proof> list(HttpServletRequest request) {
        return proofs.list(sessions.requireUser(request).id());
    }

    @PostMapping("/import")
    ResponseEntity<ProofImportService.Proof> importFile(@Valid @RequestBody ImportRequest body,
                                                         HttpServletRequest request) {
        UUID ownerId = sessions.requireUser(request).id();
        sessions.requireSameOrigin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(proofs.importFile(ownerId, body.fileId()));
    }
}

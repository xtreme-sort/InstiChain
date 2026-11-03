package edu.iitm.instichain.storage;

import edu.iitm.instichain.auth.SessionService;
import edu.iitm.instichain.auth.UserAccount;
import edu.iitm.instichain.common.ApiException;
import edu.iitm.instichain.config.InstiChainProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage/google")
public class GoogleStorageController {
    private final SessionService sessions;
    private final GoogleConnectionService connection;
    private final InstiChainProperties properties;

    public GoogleStorageController(SessionService sessions, GoogleConnectionService connection,
                                   InstiChainProperties properties) {
        this.sessions = sessions;
        this.connection = connection;
        this.properties = properties;
    }

    @PostMapping("/connect")
    Map<String, String> connect(HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        sessions.requireSameOrigin(request);
        return Map.of("authorization_url", connection.begin(user.id()).toString());
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam String state,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String error,
                                  HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        connection.consumeState(user.id(), state);
        if (error != null || code == null) {
            return redirect("error");
        }
        try {
            connection.complete(user.id(), code);
            return redirect("connected");
        } catch (ApiException failure) {
            return redirect("error");
        }
    }

    @GetMapping("/status")
    GoogleConnectionService.ConnectionStatus status(HttpServletRequest request) {
        return connection.status(sessions.requireUser(request).id());
    }

    @PostMapping("/disconnect")
    ResponseEntity<Void> disconnect(HttpServletRequest request) {
        UserAccount user = sessions.requireUser(request);
        sessions.requireSameOrigin(request);
        connection.disconnect(user.id());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Void> redirect(String outcome) {
        String app = properties.publicAppUrl().replaceAll("/+$", "");
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(app + "/?drive=" + outcome)).build();
    }
}

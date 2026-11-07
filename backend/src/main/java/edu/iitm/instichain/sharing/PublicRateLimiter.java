package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.ApiException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fixed-window, per-client limit for unauthenticated endpoints. It is process-local:
 * a multi-instance deployment must also limit at the reverse proxy.
 */
@Component
public class PublicRateLimiter {
    static final int LIMIT_PER_MINUTE = 30;
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public PublicRateLimiter() { this(Clock.systemUTC()); }

    PublicRateLimiter(Clock clock) { this.clock = clock; }

    private record Window(long startedAt, int count) {}

    public void check(String client) {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.values().removeIf(window -> now - window.startedAt() >= WINDOW_MILLIS);
        }
        Window window = windows.compute(client == null ? "unknown" : client, (key, current) ->
                current == null || now - current.startedAt() >= WINDOW_MILLIS
                        ? new Window(now, 1) : new Window(current.startedAt(), current.count() + 1));
        if (window.count() > LIMIT_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many verification requests. Try again shortly.");
        }
    }
}

package de.systemlogistics.slgos.core.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window request limiter per key, used for login and callback (PRD-0003 SK-3 AC2). In memory per instance. */
public class RateLimiter {

    private static final int MAX_KEYS = 10_000;

    private record Window(Instant start, int count) {}

    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, int limit, Duration window) {
        this.clock = clock;
        this.limit = limit;
        this.window = window;
    }

    /** Counts one request for the key; false when the key is over its limit in the current window. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> e.getValue().start().plus(window).isBefore(now));
        }
        Window result = windows.compute(key, (k, w) -> {
            if (w == null || !w.start().plus(window).isAfter(now)) {
                return new Window(now, 1);
            }
            return new Window(w.start(), w.count() + 1);
        });
        return result.count() <= limit;
    }
}

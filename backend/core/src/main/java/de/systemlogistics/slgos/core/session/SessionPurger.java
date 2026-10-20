package de.systemlogistics.slgos.core.session;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purges sessions past their timeouts hourly (PRD-0001 8.9) and keeps the {@code slg_session_active} gauge current. */
@Component
class SessionPurger {

    private static final Logger LOG = LoggerFactory.getLogger(SessionPurger.class);

    private final SessionService sessions;
    private final AtomicLong active = new AtomicLong();

    SessionPurger(SessionService sessions, MeterRegistry meters) {
        this.sessions = sessions;
        Gauge.builder("slg_session_active", active, AtomicLong::get).register(meters);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void purge() {
        try {
            int ended = sessions.purgeExpired();
            if (ended > 0) {
                LOG.info("purged {} expired sessions", ended);
            }
        } catch (RuntimeException e) {
            LOG.error("session purge failed", e);
        }
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT30S")
    void measure() {
        try {
            active.set(sessions.count());
        } catch (RuntimeException e) {
            LOG.warn("session count unavailable", e);
        }
    }
}

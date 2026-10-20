package de.systemlogistics.slgos.core.audit;

import de.systemlogistics.slgos.core.security.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Appends audit events. Callers invoke it inside their own transaction so the action and its entry commit together. */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Writes exactly one row. */
    public void record(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_event (occurred_at, event_type, actor_id, source_ip, object, outcome, reason, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Timestamp.from(clock.instant()), event.type(), event.actorId(), event.sourceIp(), event.object(),
                        event.outcome().value(), event.reason(), CorrelationId.current())
                .update();
    }
}

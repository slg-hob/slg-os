package de.systemlogistics.slgos.core.audit;

/**
 * One security event (PRD-0001 FR-9). No request bodies, tokens or business data; actor ids are pseudonymous identifiers.
 *
 * @param type    machine-readable event type such as {@code auth.login}
 * @param actorId pseudonymous user id, null for the system
 * @param object  affected object such as a session owner, null if none
 * @param reason  short code for forced actions, null otherwise
 */
public record AuditEvent(String type, String actorId, String sourceIp, String object, Outcome outcome, String reason) {

    /** Result of the audited action. */
    public enum Outcome {
        SUCCESS("success"), FAILURE("failure");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static AuditEvent success(String type, String actorId, String sourceIp) {
        return new AuditEvent(type, actorId, sourceIp, null, Outcome.SUCCESS, null);
    }
}

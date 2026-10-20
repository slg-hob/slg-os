package de.systemlogistics.slgos.core.audit;

/** Event types written in the skeleton (PRD-0003 SK-9 AC2). Failed logins and break-glass use are phase 1b. */
public final class AuditTypes {

    public static final String LOGIN = "auth.login";
    public static final String LOGOUT = "auth.logout";
    public static final String STEP_UP = "auth.step_up";
    public static final String SESSION_REVOKE = "session.revoke";
    public static final String SESSION_TERMINATED = "session.terminated";

    /** Reasons of a forced session termination. */
    public static final String IDLE_TIMEOUT = "idle_timeout";
    public static final String ABSOLUTE_TIMEOUT = "absolute_timeout";
    public static final String SESSION_CAP = "session_cap";
    public static final String BACKCHANNEL_LOGOUT = "backchannel_logout";
    public static final String REFRESH_REJECTED = "refresh_rejected";
    public static final String REFRESH_LOST = "refresh_lost";
    public static final String VAULT_UNREADABLE = "vault_unreadable";
    public static final String REPLACED = "replaced";

    private AuditTypes() {}
}

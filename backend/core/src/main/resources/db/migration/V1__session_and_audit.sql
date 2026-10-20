-- M2: server-side sessions, back-channel logout replay protection and the append-only audit table.
-- Runs as slg_migrator (owner of the objects). The roles exist before the first start (deploy/postgres/init, test support).
-- Forward-only, expand/contract discipline (PRD-0003 section 10).

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO slg_app;

-- Sessions. The id is stored only as SHA-256 hash. Tokens are AES-256-GCM ciphertexts (TokenVault); this table is not part of the backup.
CREATE TABLE session (
    id_hash          bytea       PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    user_id          text        NOT NULL,
    sid              text,
    display_name     text,
    locale           text,
    groups           text[]      NOT NULL DEFAULT '{}',
    auth_time        timestamptz,
    created_at       timestamptz NOT NULL,
    last_access      timestamptz NOT NULL,
    access_expires_at timestamptz NOT NULL,
    vault_key_id     text        NOT NULL,
    refresh_token_ct bytea       NOT NULL,
    access_token_ct  bytea       NOT NULL,
    id_token_ct      bytea       NOT NULL
);
CREATE INDEX session_user_idx ON session (user_id, created_at);
CREATE INDEX session_sid_idx ON session (sid);
GRANT SELECT, INSERT, UPDATE, DELETE ON session TO slg_app;

-- Identifiers of accepted logout tokens, to reject replays across containers.
CREATE TABLE logout_jti (
    jti     text        PRIMARY KEY,
    seen_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, DELETE ON logout_jti TO slg_app;

-- Audit events (PRD-0001 FR-9, PRD-0003 SK-9). The application role can only append and read.
CREATE TABLE audit_event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    timestamptz NOT NULL,
    event_type     text        NOT NULL,
    actor_id       text,
    source_ip      text,
    object         text,
    outcome        text        NOT NULL CHECK (outcome IN ('success', 'failure')),
    reason         text,
    correlation_id text
);
CREATE INDEX audit_event_time_idx ON audit_event (occurred_at);
CREATE INDEX audit_event_actor_idx ON audit_event (actor_id);
GRANT SELECT, INSERT ON audit_event TO slg_app;

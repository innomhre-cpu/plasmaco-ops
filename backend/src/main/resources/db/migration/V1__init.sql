-- PLASMACO Operations server schema, version 1 (Flyway).
--
-- Every synced entity (reports, stock movements, master data, users, approvals, submitted report versions) is
-- stored as its wire DTO in `records`, one row per entity holding the latest version, plus the full version history
-- in `record_history`. `seq` orders the change feed devices pull from: writers take a transaction-scoped advisory
-- lock before taking a sequence number, so sequence order equals commit order and no change is skipped by a pull.

CREATE SEQUENCE record_seq;

CREATE TABLE records (
    type              VARCHAR(40)  NOT NULL,
    id                VARCHAR(200) NOT NULL,
    version           BIGINT       NOT NULL CHECK (version > 0),
    payload           TEXT,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(64)  NOT NULL,
    updated_by_device VARCHAR(64)  NOT NULL,
    seq               BIGINT       NOT NULL,
    PRIMARY KEY (type, id),
    CHECK (deleted OR payload IS NOT NULL)
);
CREATE UNIQUE INDEX records_seq_idx ON records (seq);
CREATE INDEX records_live_type_idx ON records (type) WHERE NOT deleted;

-- Every accepted version, never changed afterwards (version preservation and investigation).
CREATE TABLE record_history (
    type              VARCHAR(40)  NOT NULL,
    id                VARCHAR(200) NOT NULL,
    version           BIGINT       NOT NULL,
    payload           TEXT,
    deleted           BOOLEAN      NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(64)  NOT NULL,
    updated_by_device VARCHAR(64)  NOT NULL,
    PRIMARY KEY (type, id, version)
);

-- Push operations already applied, by idempotency key: a retried request is answered DUPLICATE, not applied twice.
CREATE TABLE applied_ops (
    idempotency_key VARCHAR(200) PRIMARY KEY,
    device_id       VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(40)  NOT NULL,
    entity_id       VARCHAR(200) NOT NULL,
    version         BIGINT,
    applied_at      TIMESTAMPTZ  NOT NULL,
    actor_id        VARCHAR(64)  NOT NULL
);

-- Sign-in secrets: PBKDF2 hashes only; never synced to devices.
CREATE TABLE credentials (
    user_id         VARCHAR(64)  PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(300) NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ
);
CREATE UNIQUE INDEX credentials_username_idx ON credentials (lower(username));

CREATE TABLE audit_log (
    id          VARCHAR(64)  PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL,
    actor_id    VARCHAR(64)  NOT NULL,
    actor_name  VARCHAR(200) NOT NULL,
    device_id   VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(200) NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    summary     TEXT         NOT NULL
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);
CREATE INDEX audit_log_at_idx ON audit_log (at);

-- The audit log and the version history are append-only, even for the application's own database user
-- (row triggers stop UPDATE and DELETE; statement triggers stop TRUNCATE, which bypasses row triggers).
CREATE FUNCTION plasmaco_forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION plasmaco_forbid_change();
CREATE TRIGGER record_history_append_only BEFORE UPDATE OR DELETE ON record_history
    FOR EACH ROW EXECUTE FUNCTION plasmaco_forbid_change();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION plasmaco_forbid_change();
CREATE TRIGGER record_history_no_truncate BEFORE TRUNCATE ON record_history
    FOR EACH STATEMENT EXECUTE FUNCTION plasmaco_forbid_change();

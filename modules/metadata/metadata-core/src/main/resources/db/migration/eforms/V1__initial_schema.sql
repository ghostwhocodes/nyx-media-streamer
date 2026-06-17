-- V1: eForms tables (form_definitions, form_versions, media_metadata, FTS5)

CREATE TABLE IF NOT EXISTS form_definitions (
    id              VARCHAR(64)  NOT NULL,
    name            TEXT         NOT NULL,
    media_types     TEXT         NOT NULL,
    current_version INTEGER      NOT NULL,
    created_at      VARCHAR(64)  NOT NULL,
    updated_at      VARCHAR(64)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS form_versions (
    id          VARCHAR(64) NOT NULL,
    form_id     VARCHAR(64) NOT NULL REFERENCES form_definitions(id),
    version     INTEGER     NOT NULL,
    fields      TEXT        NOT NULL,
    created_at  VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_formver_formid ON form_versions (form_id);

CREATE TABLE IF NOT EXISTS media_metadata (
    id           VARCHAR(64) NOT NULL,
    media_path   TEXT        NOT NULL,
    content_hash TEXT,
    form_id      VARCHAR(64) NOT NULL REFERENCES form_definitions(id),
    form_version INTEGER     NOT NULL,
    "values"     TEXT        NOT NULL,
    created_at   VARCHAR(64) NOT NULL,
    updated_at   VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_metadata_path ON media_metadata (media_path);
CREATE INDEX IF NOT EXISTS idx_metadata_form ON media_metadata (form_id);

CREATE VIRTUAL TABLE IF NOT EXISTS metadata_fts USING fts5(metadata_id, content);

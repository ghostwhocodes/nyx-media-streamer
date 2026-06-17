-- V1: Audio probe cache table

CREATE TABLE IF NOT EXISTS file_probe_cache (
    path          TEXT    NOT NULL,
    mtime         INTEGER NOT NULL,
    size          INTEGER NOT NULL,
    duration_secs REAL,
    bitrate       INTEGER,
    channels      INTEGER,
    artist        TEXT,
    album         TEXT,
    title         TEXT,
    probed_at     TEXT    NOT NULL,
    PRIMARY KEY (path)
);

-- V1: Playlist tables

CREATE TABLE IF NOT EXISTS playlists (
    id          VARCHAR(36)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(1024) NOT NULL DEFAULT '',
    created_at  VARCHAR(64)   NOT NULL,
    updated_at  VARCHAR(64)   NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
    id          VARCHAR(36)   NOT NULL,
    playlist_id VARCHAR(36)   NOT NULL REFERENCES playlists(id),
    track_path  VARCHAR(4096) NOT NULL,
    position    INTEGER       NOT NULL,
    added_at    VARCHAR(64)   NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_track_playlist ON playlist_tracks (playlist_id);

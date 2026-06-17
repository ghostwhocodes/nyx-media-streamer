CREATE TABLE media_objects (
    object_id TEXT PRIMARY KEY NOT NULL,
    media_kind TEXT NOT NULL,
    primary_path TEXT NOT NULL,
    path_key TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    modified_at TEXT NOT NULL,
    hash_algorithm TEXT NOT NULL,
    content_hash TEXT NULL,
    display_name TEXT NOT NULL,
    duration_millis INTEGER NULL,
    width INTEGER NULL,
    height INTEGER NULL,
    channels INTEGER NULL,
    taken_at TEXT NULL,
    embedded_title TEXT NULL,
    embedded_artist TEXT NULL,
    embedded_album TEXT NULL,
    discovered_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    status TEXT NOT NULL
);

CREATE INDEX idx_media_objects_media_kind ON media_objects(media_kind);
CREATE INDEX idx_media_objects_status ON media_objects(status);

CREATE TABLE media_object_paths (
    object_id TEXT NOT NULL,
    path TEXT NOT NULL,
    path_kind TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    PRIMARY KEY (object_id, path),
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

CREATE INDEX idx_media_object_paths_object_id ON media_object_paths(object_id);
CREATE INDEX idx_media_object_paths_path_kind ON media_object_paths(path_kind);

CREATE TABLE media_object_correlations (
    correlation_id TEXT PRIMARY KEY NOT NULL,
    object_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

CREATE INDEX idx_media_object_correlations_object_id ON media_object_correlations(object_id);

CREATE TABLE media_object_correlation_payloads (
    correlation_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (correlation_id) REFERENCES media_object_correlations(correlation_id) ON DELETE CASCADE
);

CREATE TABLE media_thumbnails (
    thumbnail_id TEXT PRIMARY KEY NOT NULL,
    object_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    width INTEGER NULL,
    height INTEGER NULL,
    format TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    source_position_millis INTEGER NULL,
    is_primary INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

CREATE INDEX idx_media_thumbnails_object_id ON media_thumbnails(object_id);
CREATE INDEX idx_media_thumbnails_object_primary ON media_thumbnails(object_id, is_primary);

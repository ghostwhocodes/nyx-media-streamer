PRAGMA foreign_keys = OFF;

ALTER TABLE media_objects RENAME TO media_objects_old;
ALTER TABLE media_object_paths RENAME TO media_object_paths_old;
ALTER TABLE media_object_correlations RENAME TO media_object_correlations_old;
ALTER TABLE media_object_correlation_payloads RENAME TO media_object_correlation_payloads_old;
ALTER TABLE media_thumbnails RENAME TO media_thumbnails_old;
ALTER TABLE user_media_states RENAME TO user_media_states_old;

DROP INDEX IF EXISTS idx_media_objects_media_kind;
DROP INDEX IF EXISTS idx_media_objects_status;
DROP INDEX IF EXISTS idx_media_object_paths_object_id;
DROP INDEX IF EXISTS idx_media_object_paths_path_kind;
DROP INDEX IF EXISTS idx_media_object_correlations_object_id;
DROP INDEX IF EXISTS idx_media_thumbnails_object_id;
DROP INDEX IF EXISTS idx_media_thumbnails_object_primary;
DROP INDEX IF EXISTS idx_media_thumbnails_primary_object;
DROP INDEX IF EXISTS idx_user_media_states_user_favorite;
DROP INDEX IF EXISTS idx_user_media_states_user_continue;

CREATE TABLE media_objects (
    object_id TEXT PRIMARY KEY NOT NULL,
    media_kind TEXT NOT NULL,
    primary_path TEXT NOT NULL,
    path_key TEXT NOT NULL,
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

INSERT INTO media_objects (
    object_id,
    media_kind,
    primary_path,
    path_key,
    mime_type,
    size_bytes,
    modified_at,
    hash_algorithm,
    content_hash,
    display_name,
    duration_millis,
    width,
    height,
    channels,
    taken_at,
    embedded_title,
    embedded_artist,
    embedded_album,
    discovered_at,
    last_seen_at,
    status
)
SELECT
    object_id,
    media_kind,
    primary_path,
    path_key,
    mime_type,
    size_bytes,
    modified_at,
    hash_algorithm,
    content_hash,
    display_name,
    duration_millis,
    width,
    height,
    channels,
    taken_at,
    embedded_title,
    embedded_artist,
    embedded_album,
    discovered_at,
    last_seen_at,
    status
FROM media_objects_old;

CREATE INDEX idx_media_objects_media_kind ON media_objects(media_kind);
CREATE INDEX idx_media_objects_status ON media_objects(status);
CREATE INDEX idx_media_objects_path_key ON media_objects(path_key);

CREATE TABLE media_object_paths (
    object_id TEXT NOT NULL,
    path TEXT NOT NULL,
    path_kind TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    PRIMARY KEY (object_id, path),
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

INSERT INTO media_object_paths (
    object_id,
    path,
    path_kind,
    first_seen_at,
    last_seen_at
)
SELECT
    object_id,
    path,
    path_kind,
    first_seen_at,
    last_seen_at
FROM media_object_paths_old;

WITH ranked_primary_paths_per_object AS (
    SELECT
        object_id,
        path,
        ROW_NUMBER() OVER (
            PARTITION BY object_id
            ORDER BY last_seen_at DESC, first_seen_at DESC, path DESC
        ) AS row_number
    FROM media_object_paths
    WHERE path_kind = 'PRIMARY'
)
UPDATE media_object_paths
SET path_kind = 'HISTORICAL'
WHERE (object_id, path) IN (
    SELECT object_id, path
    FROM ranked_primary_paths_per_object
    WHERE row_number > 1
);

WITH ranked_primary_owners_per_path AS (
    SELECT
        object_id,
        path,
        ROW_NUMBER() OVER (
            PARTITION BY path
            ORDER BY last_seen_at DESC, first_seen_at DESC, object_id DESC
        ) AS row_number
    FROM media_object_paths
    WHERE path_kind = 'PRIMARY'
)
UPDATE media_object_paths
SET path_kind = 'HISTORICAL'
WHERE (object_id, path) IN (
    SELECT object_id, path
    FROM ranked_primary_owners_per_path
    WHERE row_number > 1
);

WITH reconciled_paths AS (
    SELECT
        object_id,
        path,
        MAX(CASE WHEN path_kind = 'PRIMARY' THEN 1 ELSE 0 END) OVER (
            PARTITION BY object_id
        ) AS has_primary,
        ROW_NUMBER() OVER (
            PARTITION BY object_id
            ORDER BY
                CASE WHEN path_kind = 'PRIMARY' THEN 0 ELSE 1 END,
                last_seen_at DESC,
                first_seen_at DESC,
                path DESC
        ) AS row_number
    FROM media_object_paths
)
UPDATE media_objects
SET
    primary_path = COALESCE((
        SELECT path
        FROM reconciled_paths
        WHERE reconciled_paths.object_id = media_objects.object_id
          AND reconciled_paths.row_number = 1
    ), primary_path),
    path_key = COALESCE((
        SELECT path
        FROM reconciled_paths
        WHERE reconciled_paths.object_id = media_objects.object_id
          AND reconciled_paths.row_number = 1
    ), path_key),
    status = CASE
        WHEN COALESCE((
            SELECT has_primary
            FROM reconciled_paths
            WHERE reconciled_paths.object_id = media_objects.object_id
            LIMIT 1
        ), 0) = 1 THEN status
        WHEN status IN ('DELETED', 'UNRESOLVED') THEN status
        ELSE 'MISSING'
    END;

CREATE INDEX idx_media_object_paths_object_id ON media_object_paths(object_id);
CREATE INDEX idx_media_object_paths_path_kind ON media_object_paths(path_kind);
CREATE INDEX idx_media_object_paths_path ON media_object_paths(path);
CREATE UNIQUE INDEX idx_media_object_paths_primary_object
    ON media_object_paths(object_id)
    WHERE path_kind = 'PRIMARY';
CREATE UNIQUE INDEX idx_media_object_paths_primary_path
    ON media_object_paths(path)
    WHERE path_kind = 'PRIMARY';

CREATE TABLE media_object_correlations (
    correlation_id TEXT PRIMARY KEY NOT NULL,
    object_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

INSERT INTO media_object_correlations (
    correlation_id,
    object_id,
    kind,
    created_at,
    updated_at
)
SELECT
    correlation_id,
    object_id,
    kind,
    created_at,
    updated_at
FROM media_object_correlations_old;

CREATE INDEX idx_media_object_correlations_object_id ON media_object_correlations(object_id);

CREATE TABLE media_object_correlation_payloads (
    correlation_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (correlation_id) REFERENCES media_object_correlations(correlation_id) ON DELETE CASCADE
);

INSERT INTO media_object_correlation_payloads (
    correlation_id,
    payload,
    created_at,
    updated_at
)
SELECT
    correlation_id,
    payload,
    created_at,
    updated_at
FROM media_object_correlation_payloads_old;

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

INSERT INTO media_thumbnails (
    thumbnail_id,
    object_id,
    kind,
    width,
    height,
    format,
    storage_key,
    source_position_millis,
    is_primary,
    status,
    created_at,
    updated_at
)
SELECT
    thumbnail_id,
    object_id,
    kind,
    width,
    height,
    format,
    storage_key,
    source_position_millis,
    is_primary,
    status,
    created_at,
    updated_at
FROM media_thumbnails_old;

CREATE INDEX idx_media_thumbnails_object_id ON media_thumbnails(object_id);
CREATE UNIQUE INDEX idx_media_thumbnails_primary_object
    ON media_thumbnails(object_id)
    WHERE is_primary = 1;

CREATE TABLE user_media_states (
    user_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    resume_position_millis INTEGER NULL,
    watched INTEGER NOT NULL DEFAULT 0,
    watched_at TEXT NULL,
    favorite INTEGER NOT NULL DEFAULT 0,
    rating INTEGER NULL,
    play_count INTEGER NOT NULL DEFAULT 0,
    last_played_at TEXT NULL,
    last_interaction_at TEXT NOT NULL,
    PRIMARY KEY (user_id, object_id),
    FOREIGN KEY (object_id) REFERENCES media_objects(object_id) ON DELETE CASCADE
);

INSERT INTO user_media_states (
    user_id,
    object_id,
    resume_position_millis,
    watched,
    watched_at,
    favorite,
    rating,
    play_count,
    last_played_at,
    last_interaction_at
)
SELECT
    user_id,
    object_id,
    resume_position_millis,
    watched,
    watched_at,
    favorite,
    rating,
    play_count,
    last_played_at,
    last_interaction_at
FROM user_media_states_old;

CREATE INDEX idx_user_media_states_user_favorite
    ON user_media_states(user_id, favorite, last_interaction_at);
CREATE INDEX idx_user_media_states_user_continue
    ON user_media_states(user_id, watched, last_played_at, resume_position_millis);

DROP TABLE media_object_correlation_payloads_old;
DROP TABLE media_object_correlations_old;
DROP TABLE media_thumbnails_old;
DROP TABLE user_media_states_old;
DROP TABLE media_object_paths_old;
DROP TABLE media_objects_old;

PRAGMA foreign_keys = ON;

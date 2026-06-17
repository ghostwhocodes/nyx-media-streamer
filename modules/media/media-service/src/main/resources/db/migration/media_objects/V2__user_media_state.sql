CREATE TABLE IF NOT EXISTS user_media_states (
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

CREATE INDEX IF NOT EXISTS idx_user_media_states_user_favorite
    ON user_media_states(user_id, favorite, last_interaction_at);

CREATE INDEX IF NOT EXISTS idx_user_media_states_user_continue
    ON user_media_states(user_id, watched, last_played_at, resume_position_millis);

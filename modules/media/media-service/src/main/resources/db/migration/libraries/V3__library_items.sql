CREATE TABLE library_items (
    library_item_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    parent_item_id TEXT NULL,
    source_entry_id TEXT NULL,
    source_object_id TEXT NULL,
    item_type TEXT NOT NULL,
    identity_key TEXT NOT NULL,
    title TEXT NOT NULL,
    media_kind TEXT NULL,
    primary_path TEXT NULL,
    unmatched_reason TEXT NULL,
    season_number INTEGER NULL,
    episode_number INTEGER NULL,
    track_number INTEGER NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (library_id) REFERENCES libraries(library_id) ON DELETE CASCADE,
    FOREIGN KEY (source_entry_id) REFERENCES library_entries(library_entry_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_library_items_library_identity ON library_items(library_id, identity_key);
CREATE INDEX idx_library_items_library_parent ON library_items(library_id, parent_item_id);
CREATE INDEX idx_library_items_library_type ON library_items(library_id, item_type);

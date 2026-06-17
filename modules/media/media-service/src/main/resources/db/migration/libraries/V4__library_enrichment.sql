CREATE TABLE library_item_metadata (
    library_item_id TEXT PRIMARY KEY NOT NULL,
    manual_display_title TEXT NULL,
    manual_sort_title TEXT NULL,
    manual_overview TEXT NULL,
    manual_tags_json TEXT NULL,
    imported_display_title TEXT NULL,
    imported_sort_title TEXT NULL,
    imported_overview TEXT NULL,
    imported_tags_json TEXT NULL,
    imported_source_path TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (library_item_id) REFERENCES library_items(library_item_id) ON DELETE CASCADE
);

CREATE TABLE library_item_artwork (
    library_item_id TEXT NOT NULL,
    artwork_kind TEXT NOT NULL,
    manual_path TEXT NULL,
    imported_path TEXT NULL,
    imported_source TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (library_item_id, artwork_kind),
    FOREIGN KEY (library_item_id) REFERENCES library_items(library_item_id) ON DELETE CASCADE
);

CREATE TABLE library_collections (
    collection_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    title TEXT NOT NULL,
    sort_title TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (library_id) REFERENCES libraries(library_id) ON DELETE CASCADE
);

CREATE TABLE library_collection_items (
    collection_item_id TEXT PRIMARY KEY NOT NULL,
    collection_id TEXT NOT NULL,
    library_item_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES library_collections(collection_id) ON DELETE CASCADE,
    FOREIGN KEY (library_item_id) REFERENCES library_items(library_item_id) ON DELETE CASCADE
);

CREATE INDEX idx_library_collections_library_id ON library_collections(library_id);
CREATE INDEX idx_library_collection_items_collection_id ON library_collection_items(collection_id, position);
CREATE INDEX idx_library_collection_items_item_id ON library_collection_items(library_item_id);

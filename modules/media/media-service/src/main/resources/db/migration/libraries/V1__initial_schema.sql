CREATE TABLE libraries (
    library_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    library_type TEXT NOT NULL,
    scan_status TEXT NOT NULL,
    last_scan_started_at TEXT NULL,
    last_scan_completed_at TEXT NULL,
    last_scan_failed_at TEXT NULL,
    last_scan_error TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_libraries_type ON libraries(library_type);
CREATE INDEX idx_libraries_updated_at ON libraries(updated_at);
CREATE INDEX idx_libraries_scan_status ON libraries(scan_status);

CREATE TABLE library_source_roots (
    source_root_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    root_path TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    position INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (library_id) REFERENCES libraries(library_id) ON DELETE CASCADE
);

CREATE INDEX idx_library_source_roots_library_id ON library_source_roots(library_id);
CREATE UNIQUE INDEX idx_library_source_roots_library_position ON library_source_roots(library_id, position);

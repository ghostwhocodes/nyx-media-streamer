CREATE TABLE library_scan_runs (
    scan_run_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    mode TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    started_at TEXT NULL,
    completed_at TEXT NULL,
    error_message TEXT NULL,
    files_scanned INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    refreshed_count INTEGER NOT NULL DEFAULT 0,
    missing_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (library_id) REFERENCES libraries(library_id) ON DELETE CASCADE
);

CREATE INDEX idx_library_scan_runs_library_id ON library_scan_runs(library_id);
CREATE INDEX idx_library_scan_runs_status ON library_scan_runs(status);
CREATE INDEX idx_library_scan_runs_created_at ON library_scan_runs(created_at);

CREATE TABLE library_entries (
    library_entry_id TEXT PRIMARY KEY NOT NULL,
    library_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    source_root_id TEXT NOT NULL,
    media_kind TEXT NOT NULL,
    primary_path TEXT NOT NULL,
    path_key TEXT NOT NULL,
    status TEXT NOT NULL,
    first_scanned_at TEXT NOT NULL,
    last_scanned_at TEXT NOT NULL,
    missing_at TEXT NULL,
    last_scan_run_id TEXT NULL,
    FOREIGN KEY (library_id) REFERENCES libraries(library_id) ON DELETE CASCADE,
    FOREIGN KEY (source_root_id) REFERENCES library_source_roots(source_root_id) ON DELETE CASCADE,
    FOREIGN KEY (last_scan_run_id) REFERENCES library_scan_runs(scan_run_id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX idx_library_entries_library_object ON library_entries(library_id, object_id);
CREATE INDEX idx_library_entries_library_status ON library_entries(library_id, status);
CREATE INDEX idx_library_entries_library_path_key ON library_entries(library_id, path_key);

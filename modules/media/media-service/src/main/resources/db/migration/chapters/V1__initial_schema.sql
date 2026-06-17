-- V1: Chapter mark tables

CREATE TABLE IF NOT EXISTS chapter_sets (
    id         VARCHAR(36)   NOT NULL,
    media_path VARCHAR(4096) NOT NULL,
    title      VARCHAR(255)  NOT NULL DEFAULT '',
    created_at VARCHAR(64)   NOT NULL,
    updated_at VARCHAR(64)   NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chapter_sets_media_path ON chapter_sets (media_path);

CREATE TABLE IF NOT EXISTS chapter_marks (
    id             VARCHAR(36)   NOT NULL,
    chapter_set_id VARCHAR(36)   NOT NULL REFERENCES chapter_sets(id) ON DELETE CASCADE,
    label          VARCHAR(255)  NOT NULL,
    pts_secs       DOUBLE        NOT NULL,
    notes          VARCHAR(2048) NOT NULL DEFAULT '',
    sort_order     INTEGER       NOT NULL,
    created_at     VARCHAR(64)   NOT NULL,
    updated_at     VARCHAR(64)   NOT NULL,
    PRIMARY KEY (id),
    CHECK (pts_secs >= 0),
    CHECK (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_chapter_marks_set ON chapter_marks (chapter_set_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_chapter_marks_set_sort_order
    ON chapter_marks (chapter_set_id, sort_order);

-- V1: transcode_jobs table

CREATE TABLE IF NOT EXISTS transcode_jobs (
    id               VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    input_path       TEXT         NOT NULL,
    profile          VARCHAR(128) NOT NULL,
    representation   VARCHAR(64)  NOT NULL,
    representations  TEXT         NOT NULL DEFAULT '[]',
    execution_mode   VARCHAR(32)  NOT NULL DEFAULT 'VIDEO_TRANSCODE',
    spec_key         TEXT         NOT NULL DEFAULT '',
    segments_produced INTEGER     NOT NULL DEFAULT 0,
    retry_count      INTEGER      NOT NULL DEFAULT 0,
    stderr_initial   TEXT,
    stderr_fallback  TEXT,
    created_at       VARCHAR(64)  NOT NULL,
    updated_at       VARCHAR(64)  NOT NULL,
    completed_at     VARCHAR(64),
    batch_id         VARCHAR(64),
    owner            VARCHAR(128),
    output_size_bytes BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_jobs_status  ON transcode_jobs (status);
CREATE INDEX IF NOT EXISTS idx_jobs_spec_key ON transcode_jobs (spec_key);
CREATE INDEX IF NOT EXISTS idx_jobs_updated ON transcode_jobs (updated_at);
CREATE INDEX IF NOT EXISTS idx_jobs_batch_id ON transcode_jobs (batch_id);
CREATE INDEX IF NOT EXISTS idx_jobs_owner ON transcode_jobs (owner);
CREATE INDEX IF NOT EXISTS idx_jobs_owner_status ON transcode_jobs (owner, status);

CREATE TABLE webhook_subscriptions (
    id         VARCHAR(64) PRIMARY KEY,
    url        TEXT    NOT NULL,
    secret     TEXT,
    events     TEXT    NOT NULL,
    is_active  INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);
CREATE INDEX idx_ws_active ON webhook_subscriptions(is_active);

CREATE TABLE webhook_deliveries (
    id              VARCHAR(64) PRIMARY KEY,
    subscription_id VARCHAR(64) NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,
    event           VARCHAR(64) NOT NULL,
    payload         TEXT    NOT NULL,
    status_code     INTEGER,
    attempt         INTEGER NOT NULL DEFAULT 1,
    delivered_at    TEXT,
    created_at      TEXT    NOT NULL
);
CREATE INDEX idx_wd_sub ON webhook_deliveries(subscription_id);

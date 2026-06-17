CREATE TABLE IF NOT EXISTS config_overrides (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_users (
    username      TEXT NOT NULL PRIMARY KEY,
    password_hash TEXT NOT NULL
);

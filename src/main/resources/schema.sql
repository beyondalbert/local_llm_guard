CREATE TABLE IF NOT EXISTS admin_info (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    create_time   TEXT
);

CREATE TABLE IF NOT EXISTS api_key (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash     TEXT NOT NULL UNIQUE,
    key_prefix   TEXT,
    remark       TEXT,
    enabled      INTEGER DEFAULT 1,
    expire_time  TEXT,
    allow_models TEXT,
    create_time  TEXT
);

CREATE TABLE IF NOT EXISTS request_log (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    api_key_id        INTEGER,
    model             TEXT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    request_ip        TEXT,
    cost_ms           INTEGER,
    status            INTEGER,
    error_msg         TEXT,
    request_time      TEXT
);

CREATE TABLE IF NOT EXISTS chat_session (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT,
    api_key_id  INTEGER,
    model_name  TEXT,
    temperature REAL,
    max_tokens  INTEGER,
    create_time TEXT,
    update_time TEXT
);

CREATE TABLE IF NOT EXISTS chat_message (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        INTEGER,
    role              TEXT,
    content           TEXT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    create_time       TEXT
);

CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id);

CREATE TABLE IF NOT EXISTS system_config (
    config_key   TEXT PRIMARY KEY,
    config_value TEXT
);

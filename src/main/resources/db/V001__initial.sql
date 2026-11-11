CREATE TABLE linked_accounts (
 minecraft_uuid TEXT PRIMARY KEY,
 minecraft_name TEXT NOT NULL,
 discord_id TEXT NOT NULL UNIQUE,
 created_at INTEGER NOT NULL,
 method TEXT NOT NULL
);
CREATE TABLE discord_reservations (
 minecraft_uuid TEXT PRIMARY KEY,
 discord_id TEXT NOT NULL UNIQUE,
 created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,
 renewed_at INTEGER NOT NULL DEFAULT 0,
 CHECK(expires_at > created_at)
);
CREATE TABLE verification_codes (
 code_hash TEXT PRIMARY KEY,
 discord_id TEXT NOT NULL,
 created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,
 invalidated INTEGER NOT NULL DEFAULT 0 CHECK(invalidated IN (0,1))
);
CREATE UNIQUE INDEX one_active_code ON verification_codes(discord_id) WHERE invalidated = 0;
CREATE TABLE verification_requests (discord_id TEXT PRIMARY KEY, next_allowed INTEGER NOT NULL);
CREATE TABLE verification_attempts (
 minecraft_uuid TEXT PRIMARY KEY,
 window_start INTEGER NOT NULL,
 attempts INTEGER NOT NULL,
 blocked_until INTEGER NOT NULL
);
CREATE TABLE pending_verifications (
 id TEXT PRIMARY KEY,
 actor TEXT NOT NULL,
 minecraft_uuid TEXT NOT NULL,
 minecraft_name TEXT NOT NULL,
 discord_id TEXT NOT NULL,
 discord_name TEXT NOT NULL,
 expires_at INTEGER NOT NULL
);
CREATE TABLE admin_audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 actor TEXT NOT NULL,
 action TEXT NOT NULL,
 minecraft_uuid TEXT NOT NULL,
 discord_id TEXT NOT NULL,
 timestamp INTEGER NOT NULL,
 detail TEXT NOT NULL
);
CREATE TABLE server_sessions (
 id TEXT PRIMARY KEY,
 created_at INTEGER NOT NULL,
 ended_at INTEGER,
 start_dispatched INTEGER NOT NULL DEFAULT 0,
 stop_dispatched INTEGER NOT NULL DEFAULT 0,
 save_confirmed INTEGER NOT NULL DEFAULT 0,
 result TEXT NOT NULL DEFAULT 'running'
);
CREATE TABLE lifecycle (
 singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
 state TEXT NOT NULL,
 session_id TEXT,
 deadline INTEGER NOT NULL DEFAULT 0,
 updated_at INTEGER NOT NULL,
 detail TEXT NOT NULL
);
INSERT INTO lifecycle(singleton,state,updated_at,detail) VALUES(1,'LOCKED',0,'Waiting for quorum');
CREATE TABLE config_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE events (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 timestamp INTEGER NOT NULL,
 kind TEXT NOT NULL,
 message TEXT NOT NULL,
 discord_id TEXT
);
CREATE TABLE consumer_cursors (consumer TEXT PRIMARY KEY, event_id INTEGER NOT NULL DEFAULT 0);

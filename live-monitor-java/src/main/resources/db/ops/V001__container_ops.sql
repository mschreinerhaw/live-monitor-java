CREATE TABLE IF NOT EXISTS ops_migration (version INT PRIMARY KEY, applied_at BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS ops_resource (
  id VARCHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL,
  document TEXT NOT NULL, revision BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  PRIMARY KEY (kind, id)
);
CREATE TABLE IF NOT EXISTS ops_task (
  id VARCHAR(64) PRIMARY KEY, actor VARCHAR(128) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL, host_id VARCHAR(64) NOT NULL, environment VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL, resource_id VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
  progress INT NOT NULL, request_json TEXT NOT NULL, result_json TEXT NOT NULL,
  message VARCHAR(2000) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  UNIQUE (actor, idempotency_key)
);
CREATE TABLE IF NOT EXISTS ops_audit (
  id VARCHAR(64) PRIMARY KEY, actor VARCHAR(128) NOT NULL, action VARCHAR(64) NOT NULL,
  resource_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
  outcome VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ops_lock (resource_id VARCHAR(128) PRIMARY KEY, owner VARCHAR(64) NOT NULL);

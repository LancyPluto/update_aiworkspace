CREATE TABLE IF NOT EXISTS agent_run_recovery (
  run_id BIGINT NOT NULL PRIMARY KEY,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  owner_token VARCHAR(128) NULL,
  last_error VARCHAR(1000) NULL,
  runtime_json MEDIUMTEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_agent_recovery_due (next_attempt_at, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_run_confirmations (
  run_id BIGINT NOT NULL,
  call_id VARCHAR(180) NOT NULL,
  tool_code VARCHAR(128) NOT NULL,
  approved_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (run_id, call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_run_operations (
  run_id BIGINT NOT NULL,
  operation_key VARCHAR(240) NOT NULL,
  response_json MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (run_id, operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Cover both legacy runs without a lease and expired execution leases.
SET @has_recovery_scan_index := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_runs' AND INDEX_NAME='idx_agent_runs_recovery_scan');
SET @sql := IF(@has_recovery_scan_index=0,
  'CREATE INDEX idx_agent_runs_recovery_scan ON agent_runs(status, updated_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_lease_scan_index := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_run_execution_leases' AND INDEX_NAME='idx_agent_lease_expiry');
SET @sql := IF(@has_lease_scan_index=0,
  'CREATE INDEX idx_agent_lease_expiry ON agent_run_execution_leases(lease_expires_at, run_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS agent_run_execution_leases (
  run_id BIGINT NOT NULL,
  owner_token VARCHAR(128) NOT NULL,
  lease_expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_id),
  CONSTRAINT fk_agent_run_execution_lease_run FOREIGN KEY (run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

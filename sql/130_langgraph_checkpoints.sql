CREATE TABLE IF NOT EXISTS agent_langgraph_checkpoints (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  thread_id VARCHAR(128) NOT NULL,
  checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '',
  checkpoint_id VARCHAR(128) NOT NULL,
  parent_checkpoint_id VARCHAR(128) NULL,
  checkpoint_json MEDIUMTEXT NOT NULL,
  metadata_json MEDIUMTEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_langgraph_checkpoint (run_id, thread_id, checkpoint_ns, checkpoint_id),
  KEY idx_agent_langgraph_checkpoint_head (run_id, thread_id, checkpoint_ns, id DESC),
  CONSTRAINT fk_agent_langgraph_checkpoint_run
    FOREIGN KEY (run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_langgraph_checkpoint_writes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  thread_id VARCHAR(128) NOT NULL,
  checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '',
  checkpoint_id VARCHAR(128) NOT NULL,
  task_id VARCHAR(128) NOT NULL,
  task_path VARCHAR(512) NOT NULL DEFAULT '',
  write_index INT NOT NULL,
  channel_name VARCHAR(255) NOT NULL,
  value_type VARCHAR(128) NOT NULL,
  value_base64 MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_langgraph_write (run_id, thread_id, checkpoint_ns, checkpoint_id, task_id, write_index),
  KEY idx_agent_langgraph_write_checkpoint (run_id, thread_id, checkpoint_ns, checkpoint_id, id),
  CONSTRAINT fk_agent_langgraph_write_run
    FOREIGN KEY (run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS provider_callback_registrations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(64) NOT NULL,
  task_id BIGINT NOT NULL,
  route_attempt_id BIGINT NULL,
  token_hash CHAR(64) NOT NULL,
  provider_task_id VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider_callback_token (token_hash),
  KEY idx_provider_callback_task (task_id, provider_code, created_at),
  KEY idx_provider_callback_external (provider_code, provider_task_id)
);

CREATE TABLE IF NOT EXISTS provider_callback_inbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  registration_id BIGINT NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  task_id BIGINT NOT NULL,
  provider_task_id VARCHAR(128) NOT NULL,
  callback_type VARCHAR(32) NOT NULL,
  provider_status_code INT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  process_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  UNIQUE KEY uk_provider_callback_event (
    provider_code, provider_task_id, callback_type, payload_sha256
  ),
  KEY idx_provider_callback_inbox_task (task_id, provider_code, received_at),
  KEY idx_provider_callback_inbox_pending (process_status, received_at),
  CONSTRAINT fk_provider_callback_registration
    FOREIGN KEY (registration_id) REFERENCES provider_callback_registrations(id)
);

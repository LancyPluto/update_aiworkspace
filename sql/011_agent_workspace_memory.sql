CREATE TABLE IF NOT EXISTS agent_workspaces (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  workspace_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_workspaces_owner_type (owner_user_id, workspace_type),
  KEY idx_agent_workspaces_owner (owner_user_id),
  KEY idx_agent_workspaces_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_workspace_members (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_workspace_members_workspace_user (workspace_id, user_id),
  KEY idx_agent_workspace_members_user (user_id),
  KEY idx_agent_workspace_members_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_workspace_memory_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  source_run_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_agent_workspace_memory_workspace (workspace_id, updated_at),
  KEY idx_agent_workspace_memory_user (user_id, id),
  KEY idx_agent_workspace_memory_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

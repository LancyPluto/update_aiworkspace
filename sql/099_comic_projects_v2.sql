SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS comic_projects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  description VARCHAR(1000) NULL,
  aspect_ratio VARCHAR(16) NOT NULL DEFAULT '16:9',
  visual_style VARCHAR(1000) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME NULL,
  KEY idx_comic_project_user_updated(user_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_episodes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  episode_no INT NOT NULL,
  title VARCHAR(255) NOT NULL,
  script_source_type VARCHAR(32) NOT NULL DEFAULT 'PASTE',
  script_file_name VARCHAR(255) NULL,
  script_text MEDIUMTEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 0,
  storyboard_locked_at DATETIME NULL,
  assets_confirmed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_episode_project_no(project_id, episode_no),
  KEY idx_comic_episode_project_updated(project_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_characters (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(2000) NULL,
  voice_config_json TEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_character_project_name(project_id, name),
  KEY idx_comic_character_project(project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_character_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  character_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  visual_prompt TEXT NOT NULL,
  front_image_url VARCHAR(2048) NULL,
  side_image_url VARCHAR(2048) NULL,
  back_image_url VARCHAR(2048) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_character_version(character_id, version_no),
  KEY idx_comic_character_version_status(character_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_scenes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(2000) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_scene_project_name(project_id, name),
  KEY idx_comic_scene_project(project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_scene_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scene_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  visual_prompt TEXT NOT NULL,
  anchor_image_url VARCHAR(2048) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_scene_version(scene_id, version_no),
  KEY idx_comic_scene_version_status(scene_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_shots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  episode_id BIGINT NOT NULL,
  shot_key CHAR(36) NOT NULL,
  sequence_no INT NOT NULL,
  duration_ms INT NOT NULL DEFAULT 5000,
  shot_scale VARCHAR(64) NULL,
  camera_angle VARCHAR(128) NULL,
  camera_movement VARCHAR(128) NULL,
  emotion VARCHAR(128) NULL,
  visual_description TEXT NOT NULL,
  dialogue TEXT NULL,
  narration TEXT NULL,
  sound_effect VARCHAR(1000) NULL,
  bgm_cue VARCHAR(1000) NULL,
  first_frame_prompt TEXT NULL,
  video_prompt TEXT NULL,
  negative_prompt TEXT NULL,
  character_version_ids_json TEXT NOT NULL,
  scene_version_id BIGINT NULL,
  depends_on_shot_id BIGINT NULL,
  selected_attempt_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_shot_key(shot_key),
  UNIQUE KEY uk_comic_shot_episode_sequence(episode_id, sequence_no),
  KEY idx_comic_shot_episode_status(episode_id, status),
  KEY idx_comic_shot_selected_attempt(selected_attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_generation_batches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  episode_id BIGINT NOT NULL,
  batch_type VARCHAR(32) NOT NULL DEFAULT 'SHOT_VIDEO',
  tool_code VARCHAR(128) NOT NULL,
  client_request_id VARCHAR(128) NOT NULL,
  max_parallelism INT NOT NULL DEFAULT 4,
  estimated_credits INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  request_json MEDIUMTEXT NOT NULL,
  confirmed_at DATETIME NOT NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_batch_user_request(user_id, client_request_id),
  KEY idx_comic_batch_dispatch(status, updated_at),
  KEY idx_comic_batch_episode(episode_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_shot_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  shot_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  workflow_run_id BIGINT NULL,
  root_task_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  result_json MEDIUMTEXT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(2000) NULL,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_attempt_shot_no(shot_id, attempt_no),
  UNIQUE KEY uk_comic_attempt_idempotency(idempotency_key),
  UNIQUE KEY uk_comic_attempt_workflow_run(workflow_run_id),
  UNIQUE KEY uk_comic_attempt_root_task(root_task_id),
  KEY idx_comic_attempt_batch_status(batch_id, status),
  KEY idx_comic_attempt_shot_status(shot_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comic_project_workflow_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  episode_id BIGINT NULL,
  shot_id BIGINT NULL,
  workflow_run_id BIGINT NOT NULL,
  root_task_id BIGINT NOT NULL,
  launch_source VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comic_workflow_run(workflow_run_id),
  UNIQUE KEY uk_comic_root_task(root_task_id),
  KEY idx_comic_workflow_project(project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET NAMES utf8mb4;

ALTER TABLE user_upload_assets
    ADD COLUMN history_visible TINYINT NOT NULL DEFAULT 1 AFTER storage_path;

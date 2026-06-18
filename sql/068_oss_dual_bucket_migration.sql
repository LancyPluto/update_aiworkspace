SET NAMES utf8mb4;

-- OSS 双桶迁移：wlcloudai-assets-prod → wlcloudai-assets-public + wlcloudai-assets-private
-- public 桶 (public-read): tool-covers/, customer-service/ 等公开资源
-- private 桶 (private):    用户生成的 images/, video/, audio/, digital-human/, uploads/, avatars/

-- 1. 更新 system_settings 中的桶配置
UPDATE system_settings
SET setting_value = 'https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com'
WHERE setting_key = 'assetStorage.publicBaseUrl';

INSERT INTO system_settings (setting_key, setting_value, setting_group, description)
VALUES ('assetStorage.privateBaseUrl', '/api/v1/assets/private', 'asset_storage', 'Private assets served via signed URL proxy')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

INSERT INTO system_settings (setting_key, setting_value, setting_group, description)
VALUES ('assetStorage.ossPublicBucket', 'wlcloudai-assets-public', 'asset_storage', 'Public OSS bucket (public-read ACL)')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

INSERT INTO system_settings (setting_key, setting_value, setting_group, description)
VALUES ('assetStorage.ossPrivateBucket', 'wlcloudai-assets-private', 'asset_storage', 'Private OSS bucket (private ACL, signed URL access)')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

INSERT INTO system_settings (setting_key, setting_value, setting_group, description)
VALUES ('assetStorage.ossLegacyBucket', 'wlcloudai-assets-prod', 'asset_storage', 'Legacy single bucket, kept for backward URL resolution')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

-- 2. 更新 ai_tools.cover_url: 旧桶 URL → 新公开桶 URL（工具封面是公开资源）
UPDATE ai_tools
SET cover_url = REPLACE(cover_url, 'wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com', 'wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com')
WHERE cover_url LIKE '%wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com%';

-- 3. 更新 users.avatar_url: 旧桶 → private 代理路径
UPDATE users
SET avatar_url = CONCAT('/api/v1/assets/private/', SUBSTRING_INDEX(avatar_url, '.aliyuncs.com/', -1))
WHERE avatar_url LIKE '%wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com%';

-- 4. 更新 system_settings 中的客服图片 URL
UPDATE system_settings
SET setting_value = REPLACE(setting_value, 'wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com', 'wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com')
WHERE setting_value LIKE '%wlcloudai-assets-prod.oss-cn-guangzhou.aliyuncs.com%'
  AND setting_key NOT IN ('assetStorage.ossBucketProd', 'assetStorage.switchGuide');

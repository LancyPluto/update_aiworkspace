SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Volcano Engine Ark: doubao-seedance-1-5-pro-251215
-- API reference: https://www.volcengine.com/docs/82379/1520757
-- The worker converts these canonical task fields into the provider's
-- POST /api/v3/contents/generations/tasks payload and polls the returned task.

SET @seedance_15_request_schema = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"},{"label":"首帧图生视频","value":"first_frame_to_video"},{"label":"首尾帧图生视频","value":"first_last_frame_to_video"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","accept":"image/jpeg,image/png,image/webp","required":true,"visibleWhen":{"generationMode":["first_frame_to_video","first_last_frame_to_video"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","accept":"image/jpeg,image/png,image/webp","required":true,"visibleWhen":{"generationMode":["first_last_frame_to_video"]}},{"key":"resolution","label":"分辨率","type":"string","control":"segmented","default":"720p","enum":["480p","720p","1080p"]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"segmented","default":"16:9","enum":["16:9","9:16","1:1","4:3","3:4","21:9","adaptive"]},{"key":"duration","label":"时长（秒）","type":"integer","control":"select","default":5,"enum":[-1,4,5,6,7,8,9,10,11,12]},{"key":"generateAudio","label":"生成音频","type":"boolean","default":false},{"key":"watermark","label":"添加水印","type":"boolean","default":false},{"key":"seed","label":"随机种子","type":"integer"},{"key":"cameraFixed","label":"固定镜头","type":"boolean","default":false,"visibleWhen":{"generationMode":["text_to_video"]}}]}';

-- Runtime uses the same canonical names as this schema. An empty fieldMap is
-- intentional: the provider-specific nested `content` payload is built by the
-- Seedance client, not by this flat, safe field-mapping layer.
SET @seedance_15_request_mapping = '{"version":"1","fieldMap":{}}';

SET @seedance_15_response_mapping = '{"version":"1","requestIdPath":"id","statusPath":"status","videoUrlPath":"content.video_url","lastFrameUrlPath":"content.last_frame_url","usagePath":"usage"}';

UPDATE agent_model_configs
SET request_schema_json = @seedance_15_request_schema,
    request_mapping_json = @seedance_15_request_mapping,
    response_mapping_json = @seedance_15_response_mapping,
    api_contract_version = 'v1 / 2026-09-14',
    contract_status = 'READY',
    contract_verified_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND (
    config_code = 'volcengine-gateway-video'
    OR (
      LOWER(TRIM(provider)) = 'seedance'
      AND model_name = 'doubao-seedance-1-5-pro-251215'
    )
  );

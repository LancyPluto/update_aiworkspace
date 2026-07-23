SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Model contracts are keyed by the stable production config_code. The expected
-- production IDs are retained in the seed table for audit only; environments
-- with different auto-increment values still receive the same contract.
SET @contract_version = 'docs-2026-07-23';
SET @mapping_identity = '{"version":"1","fieldMap":{}}';
SET @response_openai_text = '{"version":"1","contentPaths":["choices[0].message.content","choices[0].message.reasoning_content"],"streamContentPaths":["choices[0].delta.content"],"toolCallsPath":"choices[0].message.tool_calls","usagePath":"usage"}';
SET @response_anthropic_text = '{"version":"1","contentPath":"content[type=text].text","blocksPath":"content","usagePath":"usage"}';
SET @response_images = '{"version":"1","itemsPath":"data","urlPath":"url","base64Path":"b64_json","usagePath":"usage"}';
SET @response_async_video = '{"version":"1","requestIdPaths":["id","task_id","output.task_id","data.task_id"],"statusPaths":["status","output.task_status","data.task_status"],"urlPaths":["content.video_url","output.video_url","metadata.url","data.task_result.videos[0].url"],"usagePath":"usage"}';

SET @schema_text = '{"version":"1","fields":[{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"temperature","label":"随机性","type":"number","min":0,"max":1,"step":0.01},{"key":"top_p","label":"采样范围","type":"number","min":0,"max":1,"step":0.01},{"key":"max_tokens","label":"最大输出 Token","type":"integer","min":1}]}';

SET @schema_sf_image = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"imageSize","label":"图片尺寸","type":"string","required":true,"helpText":"使用 WIDTHxHEIGHT 格式"},{"key":"negativePrompt","label":"反向提示词","type":"string","control":"textarea"},{"key":"batchSize","label":"生成张数","type":"integer","default":1,"min":1,"max":4},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":9999999999},{"key":"numInferenceSteps","label":"推理步数","type":"integer","min":1,"max":100},{"key":"guidanceScale","label":"引导强度","type":"number","min":0,"max":20,"step":0.1}]}';
SET @mapping_sf_image = '{"version":"1","fieldMap":{}}';
SET @response_sf_image = '{"version":"1","itemsPath":"images","urlPath":"url"}';

SET @schema_sf_tts = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_speech","enum":[{"label":"文本转语音","value":"text_to_speech"}]},{"key":"input","label":"朗读文本","type":"string","control":"textarea","required":true,"minLength":1,"maxLength":128000},{"key":"voice","label":"音色","type":"string","control":"select","required":true,"default":"FunAudioLLM/CosyVoice2-0.5B:alex","enum":["FunAudioLLM/CosyVoice2-0.5B:alex","FunAudioLLM/CosyVoice2-0.5B:benjamin","FunAudioLLM/CosyVoice2-0.5B:charles","FunAudioLLM/CosyVoice2-0.5B:david","FunAudioLLM/CosyVoice2-0.5B:anna","FunAudioLLM/CosyVoice2-0.5B:bella","FunAudioLLM/CosyVoice2-0.5B:claire","FunAudioLLM/CosyVoice2-0.5B:diana"]},{"key":"responseFormat","label":"音频格式","type":"string","control":"select","default":"mp3","enum":["mp3","opus","wav","pcm"]},{"key":"sampleRate","label":"采样率","type":"integer"},{"key":"speed","label":"语速","type":"number","default":1,"min":0.25,"max":4,"step":0.05},{"key":"gain","label":"增益","type":"number","default":0,"min":-10,"max":10,"step":0.1}]}';
SET @mapping_sf_tts = '{"version":"1","fieldMap":{"responseFormat":"format"}}';
SET @response_binary_audio = '{"version":"1","contentType":"binary/audio"}';

SET @schema_sf_asr = '{"version":"1","fields":[{"key":"generationMode","label":"识别方式","type":"string","control":"segmented","required":true,"default":"speech_to_text","enum":[{"label":"语音转文字","value":"speech_to_text"}]},{"key":"file","label":"音频文件","type":"string","control":"upload","itemType":"audio","accept":"audio/*","required":true,"helpText":"最大 50MB，最长 1 小时"}]}';
SET @response_asr = '{"version":"1","textPath":"text"}';

SET @schema_gpt_image = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"图片编辑","value":"image_edit"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"输入图片","type":"array","itemType":"image","control":"upload","required":true,"minItems":1,"maxItems":16,"visibleWhen":{"generationMode":["image_edit"]}},{"key":"imageSize","label":"输出尺寸","type":"string","control":"select","default":"1024x1024","enum":[{"label":"自动","value":"auto"},{"label":"1:1","value":"1024x1024"},{"label":"3:2","value":"1536x1024"},{"label":"2:3","value":"1024x1536"}]},{"key":"quality","label":"质量","type":"string","control":"select","default":"auto","enum":["auto","low","medium","high"]},{"key":"count","label":"生成张数","type":"integer","default":1,"min":1},{"key":"responseFormat","label":"响应格式","type":"string","control":"select","default":"b64_json","enum":["b64_json","url"]}]}';
SET @mapping_gpt_image = '{"version":"1","fieldMap":{"referenceImages":"referenceImages","imageSize":"imageSize","responseFormat":"responseFormat"}}';

SET @schema_suno = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"select","required":true,"default":"generate","enum":[{"label":"音乐生成","value":"generate"},{"label":"上传音频翻唱","value":"upload_cover"},{"label":"续写已有歌曲","value":"extend"},{"label":"上传音频续写","value":"upload_extend"},{"label":"添加人声","value":"add_vocals"},{"label":"添加伴奏","value":"add_instrumental"},{"label":"替换歌曲片段","value":"replace_section"}]},{"key":"referenceAudio","label":"输入音频","type":"string","control":"upload","itemType":"audio","accept":"audio/*","required":true,"visibleWhen":{"generationMode":["upload_cover","upload_extend","add_vocals","add_instrumental"]},"helpText":"翻唱和上传续写最长 8 分钟"},{"key":"replaceSource","label":"替换来源","type":"string","control":"segmented","required":true,"default":"existing_audio","enum":[{"label":"已有歌曲","value":"existing_audio"},{"label":"上传音频","value":"uploaded_audio"}],"visibleWhen":{"generationMode":["replace_section"]}},{"key":"replaceAudio","label":"待编辑音频","type":"string","control":"upload","itemType":"audio","accept":"audio/*","required":true,"visibleWhen":{"generationMode":["replace_section"],"replaceSource":["uploaded_audio"]}},{"key":"taskId","label":"原任务 ID","type":"string","required":true,"visibleWhen":{"generationMode":["replace_section"],"replaceSource":["existing_audio"]}},{"key":"audioId","label":"原音频 ID","type":"string","required":true,"visibleWhen":{"generationMode":["replace_section"],"replaceSource":["existing_audio"]}},{"key":"extendAudioId","label":"待续写音频 ID","type":"string","required":true,"visibleWhen":{"generationMode":["extend"]}},{"key":"customMode","label":"自定义模式","type":"boolean","required":true,"default":false,"visibleWhen":{"generationMode":["generate","upload_cover"]}},{"key":"defaultParamFlag","label":"使用自定义参数","type":"boolean","required":true,"default":false,"visibleWhen":{"generationMode":["extend","upload_extend"]}},{"key":"instrumental","label":"纯音乐","type":"boolean","default":false,"visibleWhen":{"generationMode":["generate","upload_cover","upload_extend"]}},{"key":"prompt","label":"歌词或音乐描述","type":"string","control":"textarea","maxLength":5000,"visibleWhen":{"generationMode":["generate","upload_cover","extend","upload_extend","add_vocals","replace_section"]},"requiredWhen":{"anyOf":[{"generationMode":["add_vocals","replace_section"]},{"generationMode":["generate","upload_cover"],"customMode":[false]},{"generationMode":["generate","upload_cover"],"customMode":[true],"instrumental":[false]},{"generationMode":["extend"],"defaultParamFlag":[true]},{"generationMode":["upload_extend"],"defaultParamFlag":[false]},{"generationMode":["upload_extend"],"defaultParamFlag":[true],"instrumental":[false]}]},"helpText":"纯音乐自定义生成时可留空，其他必填情况由当前方式自动标记"},{"key":"style","label":"曲风","type":"string","control":"textarea","maxLength":1000,"visibleWhen":{"anyOf":[{"generationMode":["add_vocals"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]},"requiredWhen":{"anyOf":[{"generationMode":["add_vocals"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"title","label":"标题","type":"string","maxLength":100,"visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental","replace_section"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]},"requiredWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental","replace_section"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"duration","label":"时长（秒）","type":"integer","min":10,"max":360,"visibleWhen":{"generationMode":["generate"],"customMode":[true]}},{"key":"continueAt","label":"续写起点（秒）","type":"number","min":0.01,"step":0.01,"visibleWhen":{"generationMode":["extend","upload_extend"]},"requiredWhen":{"generationMode":["extend"],"defaultParamFlag":[true]}},{"key":"tags","label":"风格标签","type":"string","required":true,"visibleWhen":{"generationMode":["add_instrumental","replace_section"]}},{"key":"negativeTags","label":"排除风格","type":"string","visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental","replace_section"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]},"requiredWhen":{"generationMode":["add_vocals","add_instrumental"]}},{"key":"fullLyrics","label":"完整歌词","type":"string","control":"textarea","required":true,"visibleWhen":{"generationMode":["replace_section"]}},{"key":"infillStartS","label":"替换开始时间（秒）","type":"number","required":true,"min":0,"step":0.01,"visibleWhen":{"generationMode":["replace_section"]}},{"key":"infillEndS","label":"替换结束时间（秒）","type":"number","required":true,"min":0,"step":0.01,"visibleWhen":{"generationMode":["replace_section"]},"helpText":"替换片段需为 6 到 60 秒，且不超过原歌曲时长的一半"},{"key":"personaId","label":"Persona ID","type":"string","visibleWhen":{"anyOf":[{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"personaModel","label":"Persona 类型","type":"string","control":"segmented","default":"style_persona","enum":["style_persona","voice_persona"],"visibleWhen":{"anyOf":[{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"vocalGender","label":"人声音色","type":"string","control":"segmented","enum":["m","f"],"visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"styleWeight","label":"风格权重","type":"number","min":0,"max":1,"step":0.01,"visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"weirdnessConstraint","label":"创新度","type":"number","min":0,"max":1,"step":0.01,"visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental"]},{"generationMode":["generate","upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}},{"key":"audioWeight","label":"参考音频权重","type":"number","min":0,"max":1,"step":0.01,"visibleWhen":{"anyOf":[{"generationMode":["add_vocals","add_instrumental"]},{"generationMode":["upload_cover"],"customMode":[true]},{"generationMode":["extend","upload_extend"],"defaultParamFlag":[true]}]}}]}';
SET @mapping_suno = '{"version":"1","fieldMap":{"generationMode":"generationType","extendAudioId":"audioId","replaceAudio":"referenceAudio"}}';
SET @response_suno = '{"version":"1","requestIdPath":"data.taskId","statusPath":"data.status","itemsPath":"data.response.sunoData","urlPath":"audioUrl"}';

SET @schema_minimax_tts = '{"version":"1","fields":[{"key":"generationMode","label":"合成方式","type":"string","control":"segmented","required":true,"default":"async","enum":[{"label":"同步","value":"sync"},{"label":"异步","value":"async"}]},{"key":"text","label":"朗读文本","type":"string","control":"textarea","required":true,"minLength":1,"maxLength":50000},{"key":"voiceId","label":"音色 ID","type":"string","required":true},{"key":"speed","label":"语速","type":"number","default":1,"min":0.5,"max":2,"step":0.05},{"key":"vol","label":"音量","type":"number","default":1,"min":0.01,"max":10,"step":0.01},{"key":"pitch","label":"音调","type":"integer","default":0,"min":-12,"max":12},{"key":"emotion","label":"情绪","type":"string","control":"select","enum":["happy","sad","angry","fearful","disgusted","surprised","calm","fluent"]},{"key":"sampleRate","label":"采样率","type":"integer","control":"select","default":32000,"enum":[8000,16000,22050,24000,32000,44100]},{"key":"bitrate","label":"比特率","type":"integer","control":"select","default":128000,"enum":[32000,64000,128000,256000]},{"key":"format","label":"音频格式","type":"string","control":"select","default":"mp3","enum":["mp3","pcm","flac","wav","pcmu_raw","pcmu_wav","opus"]},{"key":"channel","label":"声道","type":"integer","control":"segmented","default":1,"enum":[1,2]}]}';
SET @mapping_minimax_tts = '{"version":"1","fieldMap":{"generationMode":"ttsMode"}}';
SET @response_minimax_tts = '{"version":"1","sync":{"audioPath":"data.audio","requestIdPath":"trace_id"},"async":{"requestIdPath":"task_id","fileIdPath":"file_id"}}';

SET @schema_minimax_music = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_music","enum":[{"label":"音乐生成","value":"text_to_music"}]},{"key":"lyrics","label":"歌词","type":"string","control":"textarea","minLength":1,"maxLength":3500,"helpText":"非纯音乐时必填；开启歌词优化且留空时由模型根据音乐描述生成歌词"},{"key":"prompt","label":"音乐描述","type":"string","control":"textarea","maxLength":2000,"helpText":"纯音乐时必填；非纯音乐时可选"},{"key":"instrumental","label":"纯音乐","type":"boolean","default":false},{"key":"lyricsOptimizer","label":"自动生成歌词","type":"boolean","default":false,"visibleWhen":{"instrumental":[false]}},{"key":"stream","label":"流式返回","type":"boolean","default":false},{"key":"outputFormat","label":"返回形式","type":"string","control":"segmented","default":"url","enum":["url","hex"]},{"key":"sampleRate","label":"采样率","type":"integer","control":"select","default":44100,"enum":[16000,24000,32000,44100]},{"key":"bitrate","label":"比特率","type":"integer","control":"select","default":128000,"enum":[32000,64000,128000,256000]},{"key":"format","label":"音频格式","type":"string","control":"select","default":"mp3","enum":["mp3","wav","pcm"]}]}';
SET @mapping_minimax_music = '{"version":"1","fieldMap":{"instrumental":"is_instrumental","lyricsOptimizer":"lyrics_optimizer","outputFormat":"output_format"}}';
SET @response_minimax_music = '{"version":"1","audioPath":"data.audio","statusPath":"data.status","requestIdPath":"trace_id"}';

DROP TEMPORARY TABLE IF EXISTS tmp_model_contract_seed_116;
CREATE TEMPORARY TABLE tmp_model_contract_seed_116 (
  expected_id BIGINT NOT NULL,
  config_code VARCHAR(128) NOT NULL PRIMARY KEY,
  provider VARCHAR(64) NULL,
  model_name VARCHAR(256) NULL,
  capabilities MEDIUMTEXT NULL,
  docs_url VARCHAR(512) NOT NULL,
  request_schema_json MEDIUMTEXT NULL,
  request_mapping_json MEDIUMTEXT NULL,
  response_mapping_json MEDIUMTEXT NULL,
  contract_status VARCHAR(32) NOT NULL DEFAULT 'READY'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_model_contract_seed_116 VALUES
  (1, 'siliconflow_digital_human', NULL, NULL, NULL, 'https://docs.siliconflow.cn/cn/api-reference/images/images-generations', @schema_sf_image, @mapping_sf_image, @response_sf_image, 'READY'),
  (2, 'siliconflow_voice_tts', NULL, NULL, NULL, 'https://docs.siliconflow.cn/cn/api-reference/audio/create-speech', @schema_sf_tts, @mapping_sf_tts, @response_binary_audio, 'READY'),
  (3, 'siliconflow_asr_teleai', NULL, NULL, NULL, 'https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions', @schema_sf_asr, @mapping_identity, @response_asr, 'READY'),
  (4, 'siliconflow_image_turbo', NULL, NULL, NULL, 'https://docs.siliconflow.cn/cn/api-reference/images/images-generations', @schema_sf_image, @mapping_sf_image, @response_sf_image, 'READY'),
  (16, '9', NULL, NULL, NULL, 'https://ofox.ai/docs/api/openai/images', @schema_gpt_image, @mapping_gpt_image, @response_images, 'READY'),
  (18, '1', 'deepseek', NULL, NULL, 'https://api-docs.deepseek.com/api/create-chat-completion', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (21, 'deepseek-v4-flash', 'deepseek', NULL, NULL, 'https://api-docs.deepseek.com/api/create-chat-completion', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (22, 'volcengine-doubao-seed-2-lite', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://www.volcengine.com/docs/82379/1494384', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (23, 'volcengine-doubao-seed-2-pro', NULL, 'doubao-seed-2-0-pro-260215', '["TEXT_GENERATION","VISION_INPUT"]', 'https://www.volcengine.com/docs/82379/1494384', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (24, 'kimi-k2-6', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://platform.kimi.com/docs/guide/kimi-k2-6-quickstart', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (25, 'zhipu-glm-5', NULL, NULL, NULL, 'https://docs.bigmodel.cn/cn/guide/models/text/glm-5', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (26, 'aliyun-qwen3-5-flash', 'qwen', NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (27, 'aliyun-qwen3-7-max', 'qwen', NULL, NULL, 'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (28, 'google-gemini-2-5-pro', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (29, 'google-gemini-2-5-flash', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (30, 'anthropic-claude-sonnet-4-6', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://platform.claude.com/docs/en/api/messages', @schema_text, @mapping_identity, @response_anthropic_text, 'READY'),
  (32, 'deepseek-chat', 'deepseek', NULL, NULL, 'https://api-docs.deepseek.com/api/create-chat-completion', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (34, 'SunoV5_5', NULL, NULL, NULL, 'https://docs.sunoapi.org/suno-api/generate-music', @schema_suno, @mapping_suno, @response_suno, 'READY'),
  (36, '3', NULL, NULL, NULL, 'https://platform.minimaxi.com/docs/api-reference/speech-t2a-http', @schema_minimax_tts, @mapping_minimax_tts, @response_minimax_tts, 'READY'),
  (38, 'aliyun-qwen3-5-plus', 'qwen', NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (39, 'volcengine-doubao-seed-1-8', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://www.volcengine.com/docs/82379/1494384', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (40, 'minimax-m2-7', NULL, NULL, NULL, 'https://platform.minimaxi.com/docs/api-reference/text-chat-openai', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (42, 'minimax-music', NULL, NULL, NULL, 'https://platform.minimaxi.com/docs/api-reference/music-generation', @schema_minimax_music, @mapping_minimax_music, @response_minimax_music, 'READY'),
  (56, 'siliconflow-z-image-turbo', NULL, NULL, NULL, 'https://docs.siliconflow.cn/cn/api-reference/images/images-generations', @schema_sf_image, @mapping_sf_image, @response_sf_image, 'READY');

SET @schema_happyhorse_t2v = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"resolution","label":"清晰度","type":"string","control":"segmented","default":"720P","enum":["720P","1080P"]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"select","default":"16:9","enum":["16:9","9:16","1:1","4:3","3:4","4:5","5:4","9:21","21:9"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":2147483647},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';
SET @schema_happyhorse_i2v = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"first_frame_to_video","enum":[{"label":"首帧图生视频","value":"first_frame_to_video"}]},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","accept":"image/jpeg,image/png,image/webp","required":true,"helpText":"最大 20MB，最小边 300px"},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"resolution","label":"清晰度","type":"string","control":"segmented","default":"720P","enum":["720P","1080P"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":2147483647},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';
SET @schema_happyhorse_r2v = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"reference_to_video","enum":[{"label":"参考图生视频","value":"reference_to_video"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","accept":"image/jpeg,image/png,image/webp","required":true,"minItems":1,"maxItems":9,"helpText":"每张最大 20MB，短边至少 400px"},{"key":"resolution","label":"清晰度","type":"string","control":"segmented","default":"720P","enum":["720P","1080P"]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"select","default":"16:9","enum":["16:9","9:16","1:1","4:3","3:4","4:5","5:4","9:21","21:9"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":2147483647},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';
SET @schema_happyhorse_edit = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"video_edit","enum":[{"label":"视频编辑","value":"video_edit"}]},{"key":"sourceVideo","label":"源视频","type":"string","control":"upload","itemType":"video","accept":"video/mp4,video/quicktime","required":true,"helpText":"MP4/MOV，3-60 秒，最大 100MB"},{"key":"prompt","label":"编辑要求","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","accept":"image/jpeg,image/png,image/webp","maxItems":5},{"key":"resolution","label":"清晰度","type":"string","control":"segmented","default":"720P","enum":["720P","1080P"]},{"key":"audioSetting","label":"音频处理","type":"string","control":"segmented","default":"auto","enum":["auto","origin"]},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":2147483647},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';
SET @response_happyhorse = '{"version":"1","requestIdPath":"output.task_id","statusPath":"output.task_status","urlPath":"output.video_url","usagePath":"usage"}';

SET @schema_agnes_image_20 = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"图生图","value":"image_to_image"},{"label":"多图合成","value":"multi_image_composition"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","required":true,"minItems":1,"visibleWhen":{"generationMode":["image_to_image","multi_image_composition"]}},{"key":"imageSize","label":"图片尺寸","type":"string","required":true},{"key":"responseFormat","label":"响应格式","type":"string","control":"segmented","default":"url","enum":["url","b64_json"]}]}';
SET @schema_agnes_image_21 = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"图生图","value":"image_to_image"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","required":true,"minItems":1,"visibleWhen":{"generationMode":["image_to_image"]}},{"key":"imageSize","label":"分辨率","type":"string","control":"segmented","default":"2K","enum":["1K","2K","3K","4K"]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"select","default":"1:1","enum":["1:1","3:4","4:3","16:9","9:16","2:3","3:2","21:9"]},{"key":"responseFormat","label":"响应格式","type":"string","control":"segmented","default":"url","enum":["url","b64_json"]}]}';
SET @schema_agnes_video = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"},{"label":"图生视频","value":"image_to_video"},{"label":"关键帧动画","value":"keyframes"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["image_to_video","keyframes"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["keyframes"]}},{"key":"numFrames","label":"总帧数","type":"integer","default":81,"min":1,"max":441,"step":8},{"key":"frameRate","label":"帧率","type":"integer","default":24,"min":1,"max":60}]}';
SET @response_agnes_video = '{"version":"1","requestIdPaths":["video_id","task_id","id"],"statusPath":"status","videoUrlPath":"video_url"}';

SET @schema_kling_t2v = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"negativePrompt","label":"反向提示词","type":"string","control":"textarea","maxLength":2500},{"key":"mode","label":"质量模式","type":"string","control":"segmented","default":"std","enum":["std","pro","4k"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15},{"key":"aspectRatio","label":"画面比例","type":"string","control":"segmented","default":"16:9","enum":["16:9","9:16","1:1"]},{"key":"sound","label":"生成声音","type":"string","control":"segmented","default":"off","enum":["on","off"]},{"key":"multiShot","label":"多分镜","type":"boolean","default":false},{"key":"shotType","label":"分镜方式","type":"string","control":"select","enum":["intelligence","customize"],"visibleWhen":{"multiShot":[true]}},{"key":"multiPrompt","label":"自定义分镜","type":"string","control":"textarea","visibleWhen":{"multiShot":[true],"shotType":["customize"]}}]}';
SET @schema_kling_i2v = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"first_frame_to_video","enum":[{"label":"首帧图生视频","value":"first_frame_to_video"},{"label":"首尾帧图生视频","value":"first_last_frame_to_video"}]},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","accept":"image/jpeg,image/png","required":true,"visibleWhen":{"generationMode":["first_frame_to_video","first_last_frame_to_video"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","accept":"image/jpeg,image/png","required":true,"visibleWhen":{"generationMode":["first_last_frame_to_video"]}},{"key":"prompt","label":"提示词","type":"string","control":"textarea","maxLength":2500},{"key":"negativePrompt","label":"反向提示词","type":"string","control":"textarea","maxLength":2500},{"key":"mode","label":"质量模式","type":"string","control":"segmented","default":"std","enum":["std","pro"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15},{"key":"sound","label":"生成声音","type":"string","control":"segmented","default":"off","enum":["on","off"]}]}';
SET @schema_kling_motion = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"motion_control","enum":[{"label":"动作控制","value":"motion_control"}]},{"key":"imageUrl","label":"角色图片","type":"string","control":"upload","itemType":"image","accept":"image/*","required":true},{"key":"videoUrl","label":"动作视频","type":"string","control":"upload","itemType":"video","accept":"video/mp4,video/quicktime","required":true},{"key":"characterOrientation","label":"角色朝向依据","type":"string","control":"segmented","required":true,"default":"image","enum":["image","video"]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","maxLength":2500},{"key":"mode","label":"质量模式","type":"string","control":"segmented","default":"std","enum":["std","pro"]},{"key":"keepOriginalSound","label":"保留原声","type":"boolean","default":false}]}';
SET @schema_kling_multi = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"multi_image_reference","enum":[{"label":"多图参考生视频","value":"multi_image_reference"}]},{"key":"imageList","label":"参考图片","type":"array","control":"upload","itemType":"image","accept":"image/*","required":true,"minItems":1,"maxItems":4},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"negativePrompt","label":"反向提示词","type":"string","control":"textarea","maxLength":2500},{"key":"mode","label":"质量模式","type":"string","control":"segmented","default":"std","enum":["std","pro"]},{"key":"duration","label":"时长（秒）","type":"integer","control":"segmented","default":5,"enum":[5,10]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"segmented","default":"16:9","enum":["16:9","9:16","1:1"]}]}';
SET @schema_kling_omni_video = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"select","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"},{"label":"首帧图生视频","value":"first_frame_to_video"},{"label":"首尾帧图生视频","value":"first_last_frame_to_video"},{"label":"参考生视频","value":"reference_to_video"},{"label":"视频编辑","value":"video_edit"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_frame_to_video","first_last_frame_to_video"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_last_frame_to_video"]}},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","required":true,"minItems":1,"visibleWhen":{"generationMode":["reference_to_video"]}},{"key":"sourceVideo","label":"源视频","type":"string","control":"upload","itemType":"video","required":true,"visibleWhen":{"generationMode":["video_edit"]}},{"key":"mode","label":"质量模式","type":"string","control":"segmented","default":"std","enum":["std","pro"]},{"key":"duration","label":"时长（秒）","type":"integer","default":5,"min":3,"max":15,"visibleWhen":{"generationMode":["text_to_video","first_frame_to_video","first_last_frame_to_video","reference_to_video"]}},{"key":"aspectRatio","label":"画面比例","type":"string","control":"segmented","default":"16:9","enum":["16:9","9:16","1:1"],"visibleWhen":{"generationMode":["text_to_video","reference_to_video"]}},{"key":"sound","label":"生成声音","type":"string","control":"segmented","default":"off","enum":["on","off"]}]}';
SET @response_kling_video = '{"version":"1","requestIdPath":"data.task_id","statusPath":"data.task_status","videoUrlPath":"data.task_result.videos[0].url"}';
SET @response_kling_image = '{"version":"1","requestIdPath":"data.task_id","statusPath":"data.task_status","itemsPath":"data.task_result.images","urlPath":"url"}';

INSERT INTO tmp_model_contract_seed_116 VALUES
  (59, 'happyhorse_i2v', NULL, NULL, NULL, 'https://help.aliyun.com/zh/model-studio/happyhorse-image-to-video-api-reference', @schema_happyhorse_i2v, @mapping_identity, @response_happyhorse, 'READY'),
  (60, 'happyhorse_r2v', NULL, NULL, '["VIDEO_GENERATION"]', 'https://help.aliyun.com/zh/model-studio/happyhorse-reference-to-video-api-reference', @schema_happyhorse_r2v, @mapping_identity, @response_happyhorse, 'READY'),
  (61, 'happyhorse_video_edit', NULL, NULL, NULL, 'https://help.aliyun.com/zh/model-studio/happyhorse-video-edit-api-reference', @schema_happyhorse_edit, @mapping_identity, @response_happyhorse, 'READY'),
  (71, 'agnes-1.5-flash', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://web.archive.org/web/20260611210950id_/https://agnes-ai.com/api/doc/agnes-15-flash?lang=en', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (72, 'agnes-2.0-flash', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://wiki.agnes-ai.com/en/docs/agnes-20-flash', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (73, 'agnes-image-2.0-flash', NULL, NULL, NULL, 'https://wiki.agnes-ai.com/en/docs/agnes-image-20-flash', @schema_agnes_image_20, @mapping_identity, @response_images, 'READY'),
  (74, 'agnes-image-2.1-flash', NULL, NULL, NULL, 'https://wiki.agnes-ai.com/en/docs/agnes-image-21-flash', @schema_agnes_image_21, @mapping_identity, @response_images, 'READY'),
  (75, 'agnes-video-v2.0', NULL, NULL, NULL, 'https://wiki.agnes-ai.com/en/docs/agnes-video-v20', @schema_agnes_video, @mapping_identity, @response_agnes_video, 'READY'),
  (76, 'kling-gateway-text-to-video', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/textToVideo', @schema_kling_t2v, @mapping_identity, @response_kling_video, 'READY'),
  (77, 'kling-gateway-image-to-video', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/imageToVideo', @schema_kling_i2v, @mapping_identity, @response_kling_video, 'READY'),
  (78, 'kling-gateway-motion-control', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/motionControl', @schema_kling_motion, @mapping_identity, @response_kling_video, 'READY'),
  (79, 'kling-gateway-multi-image-to-video', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/multiImageToVideo', @schema_kling_multi, @mapping_identity, @response_kling_video, 'READY'),
  (80, 'kling-gateway-omni-video', NULL, NULL, '["VIDEO_GENERATION"]', 'https://www.klingai.com/document-api/apiReference/model/OmniVideo', @schema_kling_omni_video, @mapping_identity, @response_kling_video, 'READY');

SET @schema_kling_image = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"图生图","value":"image_to_image"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"imageUrl","label":"参考图片","type":"string","control":"upload","itemType":"image","accept":"image/*","required":true,"visibleWhen":{"generationMode":["image_to_image"]}},{"key":"negativePrompt","label":"反向提示词","type":"string","control":"textarea","maxLength":2500,"visibleWhen":{"generationMode":["text_to_image"]}},{"key":"resolution","label":"分辨率","type":"string","control":"segmented","default":"1k","enum":["1k","2k"]},{"key":"count","label":"生成张数","type":"integer","default":1,"min":1,"max":9},{"key":"aspectRatio","label":"画面比例","type":"string","control":"select","default":"1:1","enum":["16:9","9:16","1:1","4:3","3:4","3:2","2:3","21:9"]}]}';
SET @schema_kling_omni_image = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"参考图生图","value":"reference_to_image"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true,"maxLength":2500},{"key":"imageList","label":"参考图片","type":"array","control":"upload","itemType":"image","required":true,"minItems":1,"maxItems":10,"visibleWhen":{"generationMode":["reference_to_image"]}},{"key":"resolution","label":"分辨率","type":"string","default":"1k","enum":["1k"]},{"key":"resultType","label":"结果类型","type":"string","control":"segmented","default":"single","enum":[{"label":"单图","value":"single"},{"label":"组图","value":"series"}]},{"key":"count","label":"生成张数","type":"integer","default":1,"min":1,"max":9,"visibleWhen":{"resultType":["single"]}},{"key":"seriesAmount","label":"组图张数","type":"string","control":"select","default":"auto","enum":["auto","2","3","4","5","6","7","8","9"],"visibleWhen":{"resultType":["series"]}},{"key":"aspectRatio","label":"画面比例","type":"string","default":"auto","enum":["auto"]}]}';

SET @schema_seedream = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_image","enum":[{"label":"文生图","value":"text_to_image"},{"label":"图生图","value":"image_to_image"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","required":true,"minItems":1,"maxItems":14,"visibleWhen":{"generationMode":["image_to_image"]}},{"key":"sequentialImageGeneration","label":"生成结果","type":"string","control":"segmented","default":"disabled","enum":[{"label":"单图","value":"disabled"},{"label":"组图","value":"auto"}]},{"key":"imageSize","label":"分辨率","type":"string","control":"segmented","default":"2K","enum":["2K","3K","4K"]},{"key":"aspectRatio","label":"画面比例","type":"string","control":"select","default":"1:1","enum":["1:1","16:9","9:16","4:3","3:4","3:2","2:3","21:9"]},{"key":"maxImages","label":"组图张数上限","type":"integer","default":4,"min":1,"max":15,"visibleWhen":{"sequentialImageGeneration":["auto"]}},{"key":"responseFormat","label":"响应格式","type":"string","control":"segmented","default":"url","enum":["url","b64_json"]},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';

SET @schema_seedance_15 = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"},{"label":"首帧图生视频","value":"first_frame_to_video"},{"label":"首尾帧图生视频","value":"first_last_frame_to_video"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_frame_to_video","first_last_frame_to_video"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_last_frame_to_video"]}},{"key":"resolution","label":"分辨率","type":"string","default":"720p"},{"key":"aspectRatio","label":"画面比例","type":"string","default":"16:9"},{"key":"duration","label":"时长（秒）","type":"integer","control":"select","default":5,"enum":[-1,4,5,6,7,8,9,10,11,12]},{"key":"generateAudio","label":"生成音频","type":"boolean","default":false},{"key":"watermark","label":"添加水印","type":"boolean","default":false},{"key":"seed","label":"随机种子","type":"integer"},{"key":"cameraFixed","label":"固定镜头","type":"boolean","default":false,"visibleWhen":{"generationMode":["text_to_video"]}}]}';
SET @schema_seedance_20 = '{"version":"1","requiresAnyGroups":[{"when":{"generationMode":["multimodal_reference"]},"fields":["referenceImages","referenceVideos"]}],"fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_video","enum":[{"label":"文生视频","value":"text_to_video"},{"label":"首帧图生视频","value":"first_frame_to_video"},{"label":"首尾帧图生视频","value":"first_last_frame_to_video"},{"label":"全能参考","value":"multimodal_reference"}]},{"key":"prompt","label":"提示词","type":"string","control":"textarea","required":true},{"key":"firstFrameImage","label":"首帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_frame_to_video","first_last_frame_to_video"]}},{"key":"lastFrameImage","label":"尾帧图片","type":"string","control":"upload","itemType":"image","required":true,"visibleWhen":{"generationMode":["first_last_frame_to_video"]}},{"key":"referenceImages","label":"参考图片","type":"array","control":"upload","itemType":"image","maxItems":9,"visibleWhen":{"generationMode":["multimodal_reference"]}},{"key":"referenceVideos","label":"参考视频","type":"array","control":"upload","itemType":"video","maxItems":3,"visibleWhen":{"generationMode":["multimodal_reference"]}},{"key":"referenceAudios","label":"参考音频","type":"array","control":"upload","itemType":"audio","maxItems":3,"requiresAny":["referenceImages","referenceVideos"],"visibleWhen":{"generationMode":["multimodal_reference"]},"helpText":"音频不能单独提交，需同时提供图片或视频"},{"key":"resolution","label":"分辨率","type":"string","default":"720p"},{"key":"aspectRatio","label":"画面比例","type":"string","default":"16:9"},{"key":"duration","label":"时长（秒）","type":"integer","control":"select","default":5,"enum":[-1,4,5,6,7,8,9,10,11,12,13,14,15]},{"key":"generateAudio","label":"生成音频","type":"boolean","default":false},{"key":"watermark","label":"添加水印","type":"boolean","default":false}]}';
SET @response_seedance = '{"version":"1","requestIdPath":"id","statusPath":"status","videoUrlPath":"content.video_url","lastFrameUrlPath":"content.last_frame_url","usagePath":"usage"}';

SET @schema_qwen_audio_tts = '{"version":"1","fields":[{"key":"generationMode","label":"生成方式","type":"string","control":"segmented","required":true,"default":"text_to_speech","enum":[{"label":"文本转语音","value":"text_to_speech"}]},{"key":"text","label":"朗读文本","type":"string","control":"textarea","required":true,"minLength":1},{"key":"voice","label":"音色","type":"string","required":true,"helpText":"系统音色、基础音色或复刻音色 ID"},{"key":"format","label":"音频格式","type":"string","control":"select","default":"mp3","enum":["mp3","pcm","wav","opus"]},{"key":"sampleRate","label":"采样率","type":"integer","control":"select","default":22050,"enum":[8000,16000,22050,24000,44100,48000]},{"key":"bitRate","label":"Opus 比特率（kbps）","type":"integer","default":32,"min":6,"max":510,"visibleWhen":{"format":["opus"]}},{"key":"volume","label":"音量","type":"integer","default":50,"min":0,"max":100},{"key":"rate","label":"语速","type":"number","default":1,"min":0.5,"max":2,"step":0.05},{"key":"pitch","label":"音调","type":"number","default":1,"min":0.5,"max":2,"step":0.05},{"key":"seed","label":"随机种子","type":"integer","min":0,"max":65535},{"key":"languageHints","label":"语言提示","type":"string","helpText":"多个语言代码用逗号分隔"},{"key":"instruction","label":"语音指令","type":"string","control":"textarea"},{"key":"enableSsml","label":"启用 SSML","type":"boolean","default":false}]}';
SET @response_qwen_audio_tts = '{"version":"1","requestIdPath":"request_id","audioPaths":["output.audio.url","output.audio.data"],"audioIdPath":"output.audio.id","usagePath":"usage.characters"}';

INSERT INTO tmp_model_contract_seed_116 VALUES
  (37, '4', NULL, NULL, NULL, 'https://www.volcengine.com/docs/82379/1541523', @schema_seedream, @mapping_identity, @response_images, 'READY'),
  (81, 'kling-gateway-image-generation', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/imageGeneration', @schema_kling_image, @mapping_identity, @response_kling_image, 'READY'),
  (82, 'kling-gateway-omni-image', NULL, NULL, NULL, 'https://www.klingai.com/document-api/apiReference/model/OmniImage', @schema_kling_omni_image, @mapping_identity, @response_kling_image, 'READY'),
  (107, 'volcengine-gateway-video', NULL, NULL, NULL, 'https://www.volcengine.com/docs/82379/1520757', @schema_seedance_15, @mapping_identity, @response_seedance, 'READY'),
  (108, 'volcengine-gateway-image', NULL, NULL, NULL, 'https://www.volcengine.com/docs/82379/1541523', @schema_seedream, @mapping_identity, @response_images, 'READY'),
  (113, 'qwen3_6_plus_vision_agent', NULL, NULL, NULL, 'https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (114, 'gpt-image-2_sub2api', 'openai_images_gateway', NULL, NULL, 'https://github.com/Wei-Shaw/sub2api/tree/6aeea70ee008825604ac3293ca0f216e951795d1', @schema_gpt_image, @mapping_gpt_image, @response_images, 'READY'),
  (115, 'happyhorse-1.1-t2v', 'bailian_happyhorse', NULL, '["VIDEO_GENERATION"]', 'https://help.aliyun.com/zh/model-studio/happyhorse-text-to-video-api-reference', @schema_happyhorse_t2v, @mapping_identity, @response_happyhorse, 'READY'),
  (116, 'kimi-k3', NULL, NULL, '["TEXT_GENERATION","VISION_INPUT"]', 'https://platform.kimi.com/docs/guide/kimi-k3-quickstart', @schema_text, @mapping_identity, @response_openai_text, 'READY'),
  (117, 'qwen-audio-3.0-tts-plus', 'dashscope_qwen_tts', NULL, '["TEXT_TO_SPEECH"]', 'https://help.aliyun.com/zh/model-studio/cosyvoice-tts-http-api', @schema_qwen_audio_tts, @mapping_identity, @response_qwen_audio_tts, 'READY'),
  (118, 'doubao-seedance-2-0-260128', NULL, NULL, NULL, 'https://www.volcengine.com/docs/82379/1520757', @schema_seedance_20, @mapping_identity, @response_seedance, 'READY');

DROP PROCEDURE IF EXISTS assert_model_contract_seed_116;

DELIMITER $$
CREATE PROCEDURE assert_model_contract_seed_116(IN verify_applied TINYINT)
BEGIN
  DECLARE active_count INT DEFAULT 0;
  DECLARE matched_count INT DEFAULT 0;
  DECLARE applied_count INT DEFAULT 0;
  DECLARE seed_count INT DEFAULT 0;
  DECLARE expected_id_count INT DEFAULT 0;

  SELECT COUNT(*), COUNT(DISTINCT expected_id)
  INTO seed_count, expected_id_count
  FROM tmp_model_contract_seed_116;

  IF seed_count <> 48 OR expected_id_count <> 48 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model contract seed must contain 48 unique production model IDs';
  END IF;

  SELECT COUNT(*) INTO active_count
  FROM agent_model_configs
  WHERE is_deleted = 0;

  SELECT COUNT(*) INTO matched_count
  FROM agent_model_configs model
  JOIN tmp_model_contract_seed_116 seed
    ON seed.config_code COLLATE utf8mb4_unicode_ci
     = model.config_code COLLATE utf8mb4_unicode_ci
  WHERE model.is_deleted = 0;

  IF matched_count <> active_count THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'model contract seed does not cover every active model row';
  END IF;

  IF verify_applied = 1 THEN
    SELECT COUNT(*) INTO applied_count
    FROM agent_model_configs model
    JOIN tmp_model_contract_seed_116 seed
      ON seed.config_code COLLATE utf8mb4_unicode_ci
       = model.config_code COLLATE utf8mb4_unicode_ci
    WHERE model.is_deleted = 0
      AND model.api_contract_version COLLATE utf8mb4_unicode_ci
        = @contract_version COLLATE utf8mb4_unicode_ci
      AND model.contract_status COLLATE utf8mb4_unicode_ci
        = seed.contract_status COLLATE utf8mb4_unicode_ci
      AND model.request_schema_json COLLATE utf8mb4_unicode_ci
        = seed.request_schema_json COLLATE utf8mb4_unicode_ci
      AND model.request_mapping_json COLLATE utf8mb4_unicode_ci
        = seed.request_mapping_json COLLATE utf8mb4_unicode_ci
      AND model.response_mapping_json COLLATE utf8mb4_unicode_ci
        = seed.response_mapping_json COLLATE utf8mb4_unicode_ci
      AND model.docs_url COLLATE utf8mb4_unicode_ci
        = seed.docs_url COLLATE utf8mb4_unicode_ci;

    IF applied_count <> matched_count THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'model contract seed was only partially applied';
    END IF;
  END IF;
END $$
DELIMITER ;

CALL assert_model_contract_seed_116(0);

UPDATE agent_model_configs model
JOIN tmp_model_contract_seed_116 seed
  ON seed.config_code COLLATE utf8mb4_unicode_ci
   = model.config_code COLLATE utf8mb4_unicode_ci
SET model.provider = COALESCE(seed.provider COLLATE utf8mb4_unicode_ci, model.provider COLLATE utf8mb4_unicode_ci),
    model.model_name = COALESCE(seed.model_name COLLATE utf8mb4_unicode_ci, model.model_name COLLATE utf8mb4_unicode_ci),
    model.capabilities = COALESCE(seed.capabilities COLLATE utf8mb4_unicode_ci, model.capabilities COLLATE utf8mb4_unicode_ci),
    model.docs_url = seed.docs_url,
    model.request_schema_json = seed.request_schema_json,
    model.request_mapping_json = seed.request_mapping_json,
    model.response_mapping_json = seed.response_mapping_json,
    model.api_contract_version = @contract_version,
    model.contract_status = seed.contract_status,
    model.contract_verified_at = CASE WHEN seed.contract_status = 'READY' THEN CURRENT_TIMESTAMP ELSE NULL END,
    model.updated_at = CURRENT_TIMESTAMP
WHERE model.is_deleted = 0;

CALL assert_model_contract_seed_116(1);
DROP PROCEDURE IF EXISTS assert_model_contract_seed_116;

-- These rows were copied from unrelated pricing templates. Correct the unit,
-- but preserve all price numbers for an explicit pricing review.
UPDATE agent_model_configs
SET billing_unit = 'PER_SECOND', updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0 AND config_code = 'happyhorse-1.1-t2v';

UPDATE agent_model_configs
SET billing_unit = 'PER_CHARACTER', updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0 AND config_code = 'qwen-audio-3.0-tts-plus';

UPDATE ai_tools tool
JOIN agent_model_configs model
  ON model.config_code = 'happyhorse-1.1-t2v'
 AND model.is_deleted = 0
SET tool.model_config_id = model.id,
    tool.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'happyhorse_text_to_video'
  AND tool.is_deleted = 0;

DELETE binding
FROM tool_model_bindings binding
JOIN ai_tools tool ON tool.id = binding.tool_id
JOIN agent_model_configs model
  ON model.config_code = 'happyhorse-1.1-t2v'
 AND model.is_deleted = 0
WHERE tool.tool_code = 'happyhorse_text_to_video'
  AND binding.model_config_id <> model.id;

INSERT INTO tool_model_bindings(tool_id, model_config_id, is_default, sort_order, created_at, updated_at)
SELECT tool.id, model.id, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ai_tools tool
JOIN agent_model_configs model
  ON model.config_code = 'happyhorse-1.1-t2v'
 AND model.is_deleted = 0
WHERE tool.tool_code = 'happyhorse_text_to_video'
  AND tool.is_deleted = 0
ON DUPLICATE KEY UPDATE
  is_default = VALUES(is_default),
  sort_order = VALUES(sort_order),
  updated_at = CURRENT_TIMESTAMP;

DROP TEMPORARY TABLE IF EXISTS tmp_model_contract_seed_116;

package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingVersionMapper;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRouterSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentMemorySettings;
import com.aiminilab.aitoolmarket.agent.config.AgentOutboundProxySettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRuntimeSettings;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.credit.service.ReferralCodeService;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.user.service.PublicUserIdentityService;
import com.aiminilab.aitoolmarket.tool.config.ToolTemplateBootstrap;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PublicUserIdentityService publicUserIdentityService;
    private final ReferralCodeService referralCodeService;
    private final ToolCategoryMapper toolCategoryMapper;
    private final SystemSettingMapper systemSettingMapper;
    private final SystemSettingVersionMapper systemSettingVersionMapper;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final ToolTemplateBootstrap toolTemplateBootstrap;
    private final ModelVendorAccountMigrationService modelVendorAccountMigrationService;
    private final ModelProviderRegistry modelProviderRegistry;
    private final AppProperties appProperties;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public DataInitializer(UserMapper userMapper, PublicUserIdentityService publicUserIdentityService,
                           ReferralCodeService referralCodeService,
                           ToolCategoryMapper toolCategoryMapper,
                           SystemSettingMapper systemSettingMapper, SystemSettingVersionMapper systemSettingVersionMapper,
                           PasswordEncoder passwordEncoder,
                           JdbcTemplate jdbcTemplate, ToolTemplateBootstrap toolTemplateBootstrap,
                           ModelVendorAccountMigrationService modelVendorAccountMigrationService,
                           ModelProviderRegistry modelProviderRegistry,
                           AppProperties appProperties) {
        this.userMapper = userMapper;
        this.publicUserIdentityService = publicUserIdentityService;
        this.referralCodeService = referralCodeService;
        this.toolCategoryMapper = toolCategoryMapper;
        this.systemSettingMapper = systemSettingMapper;
        this.systemSettingVersionMapper = systemSettingVersionMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = jdbcTemplate.getDataSource();
        this.toolTemplateBootstrap = toolTemplateBootstrap;
        this.modelVendorAccountMigrationService = modelVendorAccountMigrationService;
        this.modelProviderRegistry = modelProviderRegistry;
        this.appProperties = appProperties;
    }

    @Override
    public void run(String... args) {
        ensureSchemaCompatibility();
        publicUserIdentityService.backfillMissingCodes();
        referralCodeService.backfillMissingCodes();
        seedModelProviderMetadata();
        modelVendorAccountMigrationService.migrateIfNeeded();
        seedGptImageApiKeysFromEnv();
        toolTemplateBootstrap.ensureSchemaAndSeed();
        normalizeGptImageToolFieldOptions();
        createUserIfAbsent("admin", "123456", "Admin", UserType.ADMIN);
        createUserIfAbsent("user1", "123456", "User One", UserType.USER);
        toolCategoryMapper.ensureDefaultCategory();
        toolCategoryMapper.retireLegacyCategories();
        seedAgnesTextToVideoTool();
        seedDefaultTextToImageTool();
        normalizeToolAndModelCapabilities();
        systemSettingMapper.ensureTable();
        systemSettingVersionMapper.ensureTable();
        seedAgentPromptSettings();
    }

    private void seedAgentPromptSettings() {
        seedSettingDefaults(AgentPromptSettings.defaults(), "agent", "Agent prompt setting");
        seedSettingDefaults(AgentRouterSettings.defaults(), "agent", "Agent router setting");
        seedSettingDefaults(AgentMemorySettings.defaults(), "agent", "Agent memory setting");
        seedSettingDefaults(AgentRuntimeSettings.defaults(), "agent", "Agent runtime setting");
        seedSettingDefaults(AgentOutboundProxySettings.defaults(), "agent", "Outbound proxy setting");
    }

    private void seedSettingDefaults(java.util.Map<String, String> defaults, String group, String description) {
        defaults.forEach((key, value) -> systemSettingMapper.insertIfAbsent(key, value, group, description));
    }

    private void seedAgnesTextToVideoTool() {
        executeSql("""
                INSERT INTO ai_tools (
                  tool_code, tool_name, category_id, description, cover_url, status,
                  estimated_credit_cost, model_config_id, tool_type, input_modality,
                  output_modality, config_note, template_id, execution_handler,
                  created_by, updated_by, is_deleted
                )
                SELECT
                  'agnes_text_to_video',
                  'Agnes 视频生成',
                  c.id,
                  '输入提示词，可选上传参考图；未上传图片走文生视频，上传图片走图文生视频。',
                  '/workspace-assets/tool-ai-video.jpg',
                  'ONLINE',
                  5,
                  m.id,
                  'VIDEO_GENERATION',
                  'MULTIMODAL',
                  'VIDEO',
                  '<!-- ai-tool-ui:{"primaryColor":"#ff2f6d","welcomeMessage":"","mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"Agnes 视频生成","heroSubtitle":"输入提示词，可选上传参考图；未上传图片走文生视频，上传图片走图文生视频。","demoThumbnails":[],"useCases":["短视频创作","产品展示","剧情分镜"],"steps":["输入提示词","可选上传参考图","点击生成"],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->',
                  tt.id,
                  'VIDEO_GENERATION',
                  1,
                  1,
                  0
                FROM (
                  SELECT id
                  FROM agent_model_configs
                  WHERE COALESCE(is_deleted, 0) = 0
                    AND enabled = 1
                    AND COALESCE(agent_enabled, 0) = 1
                    AND UPPER(COALESCE(capabilities, '')) LIKE '%VIDEO_GENERATION%'
                    AND (
                      provider = 'agnes_video'
                      OR model_name = 'agnes-video-v2.0'
                      OR config_code LIKE '%agnes_video_v2_0%'
                    )
                  ORDER BY
                    CASE WHEN model_name = 'agnes-video-v2.0' THEN 0 ELSE 1 END,
                    COALESCE(is_default, 0) DESC,
                    id DESC
                  LIMIT 1
                ) m
                LEFT JOIN tool_categories c ON c.category_code = 'text-to-video'
                LEFT JOIN tool_templates tt ON tt.template_code = 'video_generation_default'
                WHERE NOT EXISTS (
                  SELECT 1 FROM ai_tools
                  WHERE tool_code = 'agnes_text_to_video'
                )
                """);
        executeSql("""
                UPDATE ai_tools
                SET tool_name = 'Agnes 视频生成',
                    category_id = COALESCE((SELECT id FROM tool_categories WHERE category_code = 'text-to-video' LIMIT 1), category_id),
                    description = '输入提示词，可选上传参考图；未上传图片走文生视频，上传图片走图文生视频。',
                    cover_url = COALESCE(cover_url, '/workspace-assets/tool-ai-video.jpg'),
                    status = 'ONLINE',
                    estimated_credit_cost = 5,
                    model_config_id = COALESCE((
                      SELECT id
                      FROM agent_model_configs
                      WHERE COALESCE(is_deleted, 0) = 0
                        AND enabled = 1
                        AND COALESCE(agent_enabled, 0) = 1
                        AND UPPER(COALESCE(capabilities, '')) LIKE '%VIDEO_GENERATION%'
                        AND (
                          provider = 'agnes_video'
                          OR model_name = 'agnes-video-v2.0'
                          OR config_code LIKE '%agnes_video_v2_0%'
                        )
                      ORDER BY
                        CASE WHEN model_name = 'agnes-video-v2.0' THEN 0 ELSE 1 END,
                        COALESCE(is_default, 0) DESC,
                        id DESC
                      LIMIT 1
                    ), model_config_id),
                    tool_type = 'VIDEO_GENERATION',
                    input_modality = 'MULTIMODAL',
                    output_modality = 'VIDEO',
                    config_note = '<!-- ai-tool-ui:{"primaryColor":"#ff2f6d","welcomeMessage":"","mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"Agnes 视频生成","heroSubtitle":"输入提示词，可选上传参考图；未上传图片走文生视频，上传图片走图文生视频。","demoThumbnails":[],"useCases":["短视频创作","产品展示","剧情分镜"],"steps":["输入提示词","可选上传参考图","点击生成"],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->',
                    template_id = COALESCE((SELECT id FROM tool_templates WHERE template_code = 'video_generation_default' LIMIT 1), template_id),
                    execution_handler = 'VIDEO_GENERATION',
                    is_deleted = 0,
                    updated_by = 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tool_code = 'agnes_text_to_video'
                """);
        executeSql("""
                INSERT INTO tool_field_schemas (tool_id, schema_version, status, created_by)
                SELECT t.id, 'v1.0.1', 'ACTIVE', 1
                FROM ai_tools t
                WHERE t.tool_code = 'agnes_text_to_video'
                  AND t.is_deleted = 0
                  AND NOT EXISTS (
                    SELECT 1 FROM tool_field_schemas s
                    WHERE s.tool_id = t.id AND s.status = 'ACTIVE'
                  )
                """);
        seedAgnesTextToVideoField("prompt", "视频描述", "textarea",
                "描述镜头、主体、动作、风格和氛围", null, true, true, true, null, "ask_user", "MEDIUM", 1);
        seedAgnesTextToVideoField("aspectRatio", "视频比例", "radio",
                null, "[{\"label\":\"16:9\",\"value\":\"16:9\"},{\"label\":\"9:16\",\"value\":\"9:16\"},{\"label\":\"1:1\",\"value\":\"1:1\"}]",
                false, false, false, "16:9", "default", "LOW", 2);
        seedAgnesTextToVideoField("duration", "视频时长", "radio",
                null, "[{\"label\":\"5 秒\",\"value\":\"5\"},{\"label\":\"10 秒\",\"value\":\"10\"},{\"label\":\"15 秒\",\"value\":\"15\"}]",
                false, false, false, "5", "default", "LOW", 3);
        seedAgnesTextToVideoField("referenceImageUrl", "参考图片", "image_upload",
                "上传参考图片后走图文生视频；不上传则走文生视频。",
                null, false, false, false, null, "context", "LOW", 4);
    }

    private void seedDefaultTextToImageTool() {
        executeSql("""
                INSERT INTO ai_tools (
                  tool_code, tool_name, category_id, description, cover_url, status,
                  estimated_credit_cost, model_config_id, tool_type, input_modality,
                  output_modality, config_note, template_id, execution_handler,
                  created_by, updated_by, is_deleted
                )
                SELECT
                  'gpt_image_text_to_image',
                  'AI 文生图',
                  c.id,
                  '输入提示词直接生成图片；上传参考图后走图文生图，未上传图片走文生图。模型由首页选择项决定。',
                  '/workspace-assets/feature-gpt-image.jpg',
                  'ONLINE',
                  4,
                  m.id,
                  'IMAGE_GENERATION',
                  'MULTIMODAL',
                  'IMAGE',
                  '<!-- ai-tool-ui:{"primaryColor":"#ff2f6d","welcomeMessage":"","mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","heroTitle":"AI 文生图","heroSubtitle":"输入提示词直接生成图片；上传参考图后走图文生图，未上传图片走文生图。模型由首页选择项决定。","demoThumbnails":[],"useCases":["角色设定","商品图","海报视觉"],"steps":["输入提示词","可选上传参考图","选择生图模型","点击生成"],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->',
                  tt.id,
                  'IMAGE_GENERATION',
                  1,
                  1,
                  0
                FROM (
                  SELECT id
                  FROM agent_model_configs
                  WHERE COALESCE(is_deleted, 0) = 0
                    AND enabled = 1
                    AND COALESCE(agent_enabled, 0) = 1
                    AND UPPER(COALESCE(capabilities, '')) LIKE '%IMAGE_GENERATION%'
                  ORDER BY
                    CASE WHEN provider = 'openai_images_gateway' THEN 0 ELSE 1 END,
                    COALESCE(is_default, 0) DESC,
                    id DESC
                  LIMIT 1
                ) m
                LEFT JOIN tool_categories c ON c.category_code = 'text-to-image'
                LEFT JOIN tool_templates tt ON tt.template_code = 'image_generation_default'
                WHERE NOT EXISTS (
                  SELECT 1 FROM ai_tools
                  WHERE tool_code = 'gpt_image_text_to_image'
                )
                """);
        executeSql("""
                UPDATE ai_tools
                SET tool_name = 'AI 文生图',
                    category_id = COALESCE((SELECT id FROM tool_categories WHERE category_code = 'text-to-image' LIMIT 1), category_id),
                    description = '输入提示词直接生成图片；上传参考图后走图文生图，未上传图片走文生图。模型由首页选择项决定。',
                    cover_url = COALESCE(cover_url, '/workspace-assets/feature-gpt-image.jpg'),
                    status = 'ONLINE',
                    estimated_credit_cost = 4,
                    model_config_id = COALESCE((
                      SELECT id
                      FROM agent_model_configs
                      WHERE COALESCE(is_deleted, 0) = 0
                        AND enabled = 1
                        AND COALESCE(agent_enabled, 0) = 1
                        AND UPPER(COALESCE(capabilities, '')) LIKE '%IMAGE_GENERATION%'
                      ORDER BY
                        CASE WHEN provider = 'openai_images_gateway' THEN 0 ELSE 1 END,
                        COALESCE(is_default, 0) DESC,
                        id DESC
                      LIMIT 1
                    ), model_config_id),
                    tool_type = 'IMAGE_GENERATION',
                    input_modality = 'MULTIMODAL',
                    output_modality = 'IMAGE',
                    config_note = '<!-- ai-tool-ui:{"primaryColor":"#ff2f6d","welcomeMessage":"","mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"AI 文生图","heroSubtitle":"输入提示词直接生成图片；上传参考图后走图文生图，未上传图片走文生图。模型由首页选择项决定。","demoThumbnails":[],"useCases":["角色设定","商品图","海报视觉"],"steps":["输入提示词","可选上传参考图","选择生图模型","点击生成"],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->',
                    template_id = COALESCE((SELECT id FROM tool_templates WHERE template_code = 'image_generation_default' LIMIT 1), template_id),
                    execution_handler = 'IMAGE_GENERATION',
                    is_deleted = 0,
                    updated_by = 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tool_code = 'gpt_image_text_to_image'
                """);
        executeSql("""
                INSERT INTO tool_field_schemas (tool_id, schema_version, status, created_by)
                SELECT t.id, 'v1.0.1', 'ACTIVE', 1
                FROM ai_tools t
                WHERE t.tool_code = 'gpt_image_text_to_image'
                  AND t.is_deleted = 0
                  AND NOT EXISTS (
                    SELECT 1 FROM tool_field_schemas s
                    WHERE s.tool_id = t.id AND s.status = 'ACTIVE'
                  )
                """);
        seedToolField("gpt_image_text_to_image", "prompt", "提示词", "textarea",
                "描述主体、场景、风格、构图和细节", null, true, true, true, null, "ask_user", "MEDIUM", 1);
        seedToolField("gpt_image_text_to_image", "sourceImageUrl", "参考图片", "image_upload",
                "可选上传参考图片；未上传图片走文生图，上传图片走图文生图。",
                null, false, false, false, null, "context", "LOW", 2);
        executeSql("""
                UPDATE tool_field_schema_items
                SET field_name = '提示词',
                    field_type = 'textarea',
                    placeholder = '描述主体、场景、风格、构图和细节',
                    required = 1,
                    execution_required = 1,
                    user_required = 1,
                    agent_fill_strategy = 'ask_user',
                    risk_level = 'MEDIUM',
                    sort_order = 1,
                    status = 'ACTIVE'
                WHERE field_key = 'prompt'
                  AND schema_id IN (
                    SELECT s.id
                    FROM tool_field_schemas s
                    JOIN ai_tools t ON t.id = s.tool_id
                    WHERE t.tool_code = 'gpt_image_text_to_image'
                      AND t.is_deleted = 0
                      AND s.status = 'ACTIVE'
                  )
                """);
        executeSql("""
                UPDATE tool_field_schema_items
                SET field_name = '参考图片',
                    field_type = 'image_upload',
                    placeholder = '可选上传参考图片；未上传图片走文生图，上传图片走图文生图。',
                    options_json = NULL,
                    required = 0,
                    execution_required = 0,
                    user_required = 0,
                    default_value = NULL,
                    agent_fill_strategy = 'context',
                    risk_level = 'LOW',
                    sort_order = 2,
                    status = 'ACTIVE'
                WHERE field_key = 'sourceImageUrl'
                  AND schema_id IN (
                    SELECT s.id
                    FROM tool_field_schemas s
                    JOIN ai_tools t ON t.id = s.tool_id
                    WHERE t.tool_code = 'gpt_image_text_to_image'
                      AND t.is_deleted = 0
                      AND s.status = 'ACTIVE'
                  )
                """);
    }

    private void seedAgnesTextToVideoField(String fieldKey,
                                           String fieldName,
                                           String fieldType,
                                           String placeholder,
                                           String optionsJson,
                                           boolean required,
                                           boolean executionRequired,
                                           boolean userRequired,
                                           String defaultValue,
                                           String agentFillStrategy,
                                           String riskLevel,
                                           int sortOrder) {
        seedToolField("agnes_text_to_video", fieldKey, fieldName, fieldType, placeholder, optionsJson,
                required, executionRequired, userRequired, defaultValue, agentFillStrategy, riskLevel, sortOrder);
    }

    private void seedToolField(String toolCode,
                               String fieldKey,
                               String fieldName,
                               String fieldType,
                               String placeholder,
                               String optionsJson,
                               boolean required,
                               boolean executionRequired,
                               boolean userRequired,
                               String defaultValue,
                               String agentFillStrategy,
                               String riskLevel,
                               int sortOrder) {
        executeSql("""
                INSERT INTO tool_field_schema_items (
                  schema_id, field_key, field_name, field_type, placeholder, options_json,
                  required, execution_required, user_required, default_value,
                  agent_fill_strategy, risk_level, sort_order, status
                )
                SELECT s.id, '%s', '%s', '%s', %s, %s, %d, %d, %d, %s, '%s', '%s', %d, 'ACTIVE'
                FROM tool_field_schemas s
                JOIN ai_tools t ON t.id = s.tool_id
                WHERE t.tool_code = '%s'
                  AND t.is_deleted = 0
                  AND s.status = 'ACTIVE'
                  AND NOT EXISTS (
                    SELECT 1 FROM tool_field_schema_items i
                    WHERE i.schema_id = s.id AND i.field_key = '%s'
                  )
                ORDER BY s.id DESC
                LIMIT 1
                """.formatted(
                sqlString(fieldKey),
                sqlString(fieldName),
                sqlString(fieldType),
                sqlNullableString(placeholder),
                sqlNullableString(optionsJson),
                required ? 1 : 0,
                executionRequired ? 1 : 0,
                userRequired ? 1 : 0,
                sqlNullableString(defaultValue),
                sqlString(agentFillStrategy),
                sqlString(riskLevel),
                sortOrder,
                sqlString(toolCode),
                sqlString(fieldKey)
        ));
    }

    private static String sqlNullableString(String value) {
        return value == null ? "NULL" : "'" + sqlString(value) + "'";
    }

    private static String sqlString(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private void seedImageGenerationSkillBundle() {
        String toolCodes = "[\"gpt_image\",\"gpt_image2\",\"openai_image\",\"openai_images\",\"image_generation\"]";
        String examples = """
                [
                  {
                    "user": "同样对这张图生成相同效果的图片，人物模特还是我刚刚上传的那张",
                    "operation": "composite",
                    "notes": "从 SessionState 继承上一张图的视觉 prompt，结合当前附件作为 face_ref/identity_ref。"
                  }
                ]
                """;
        String sop = """
                You are using the image_generation Skill Bundle. These rules are mandatory for the next image tool call.

                Contract:
                - The image tool exposed to you is v2-lite. Use semantic fields such as operation, generation_prompt, base_image_ref, base_prompt, modification_prompt, references, aspect_ratio, count, negative_prompt, and routing_notes.
                - Never send an empty prompt, placeholder prompt, or context-only phrase.
                - Do not ask the user to write the prompt when the current <SessionState> contains enough visual context. Prompt synthesis is your core responsibility as an image agent.

                Prompt completeness:
                - Any image_generation call must contain a standalone visual prompt that can be executed without reading the chat history.
                - For generate/composite, generation_prompt must fully describe subject, scene, composition, camera, lighting, style, mood, and requested changes.
                - For edit/variation, copy the selected SessionState image visual prompt into base_prompt, then put only the new visual change into modification_prompt.
                - If the user continues from previous output, read <SessionState> and merge the relevant visual base into the tool arguments yourself.

                Reference routing:
                - Put all current reference images in references[] with roles such as face_ref, identity_ref, style_ref, pose_ref, composition_ref, background_ref, object_ref, or supplemental_ref.
                - Use current attachment aliases such as [当前参考图_1] in references[].source_ref.
                - Never let old @图片 labels from a previous base prompt override current-turn attachment aliases.
                - Explain routing briefly in routing_notes, for example: current attachment controls identity; latest generated image controls composition and style.
                - If you cannot actually inspect the pixels of a reference image, do not invent specific visual details from it. State only the role constraints in generation_prompt and references[].notes, such as "follow [当前参考图_1] for pose/composition only".
                - For pure multi-reference synthesis from current attachments, use operation=composite and do not set base_image_ref. Use base_image_ref only for edit/variation of an existing base image.

                Self-correction:
                - If a SchemaValidationError says a prompt field is missing, do not explain the error to the user.
                - Immediately read <SessionState>, synthesize the missing complete visual prompt, and call the image tool again.
                - Only ask the user for clarification after the runtime has already allowed the final failure.
                """;
        executeSql("""
                INSERT INTO agent_skill_bundles (
                  skill_code, display_name, description, tool_codes_json, sop_rules,
                  when_to_use, when_not_to_use, field_policy_json, examples_json,
                  status, version, published_at, created_at, updated_at
                )
                SELECT
                  'image_generation',
                  '图像生成',
                  '生成、编辑、融合或续作图片；支持多参考图角色路由、上下文继承和 v2-lite 生图参数。',
                  %s,
                  %s,
                  '用户请求生成图片、编辑已有图片、基于上一张图续作、融合多张参考图、迁移身份/风格/构图时使用。',
                  '用户只是闲聊、询问解释、或请求视频/音乐/PPT 等非图片输出时不要使用。',
                  '{}',
                  %s,
                  'PUBLISHED',
                  1,
                  CURRENT_TIMESTAMP,
                  CURRENT_TIMESTAMP,
                  CURRENT_TIMESTAMP
                WHERE NOT EXISTS (
                  SELECT 1 FROM agent_skill_bundles WHERE skill_code = 'image_generation'
                )
                """.formatted(sqlNullableString(toolCodes), sqlNullableString(sop), sqlNullableString(examples)));
    }

    private void seedMusicGenerationSkillBundle() {
        String toolCodes = "[\"suno_music\",\"suno\",\"music_generation\"]";
        String examples = """
                [
                  {
                    "user": "模仿 Owl City 的 good time 风格，创作一首日系女团歌曲",
                    "tool": "suno_music",
                    "arguments": {
                      "customMode": false,
                      "prompt": "Upbeat Japanese girl-group electropop with bright synth arpeggios, handclaps, sunny festival energy, clean youthful vocals, catchy chorus, optimistic summer-night mood. Original melody and lyrics only.",
                      "model": "V5_5"
                    },
                    "notes": "只提炼非侵权风格特征；非 custom 模式 prompt 控制在 500 字以内。"
                  },
                  {
                    "user": "我写了一整段歌词，帮我做成流行摇滚歌曲",
                    "tool": "suno_music",
                    "arguments": {
                      "customMode": true,
                      "prompt": "用户提供的完整歌词",
                      "style": "Mandarin pop rock, energetic drums, emotional guitar, polished idol vocal production",
                      "title": "根据歌词主题生成的短标题"
                    },
                    "notes": "长歌词或完整歌词必须进入 customMode=true。"
                  }
                ]
                """;
        String sop = """
                You are using the music_generation Skill Bundle. These rules are mandatory for the next music tool call.

                Contract:
                - Use this skill for Suno-style music generation tools such as suno_music, suno, and music_generation.
                - The tool runs with its backend-bound model. Do not invent a different execution model.
                - Keep model/version selection in the tool's model field. Do not create or request a separate skill for each Suno model version.

                Prompt mode policy:
                - When customMode=false, prompt must be a compact music brief of 500 characters or fewer.
                - In non-custom mode, summarize genre, mood, tempo/energy, instrumentation, vocal direction, language, and originality constraints. Do not paste long lyrics.
                - If the user provides full lyrics, a long poem, verse/chorus sections, or asks to preserve exact lyrics, set customMode=true and put the lyrics in prompt.
                - In customMode=true, use style for genre/arrangement tags and title for a short song title when available or safely inferable.
                - If instrumental=true, do not write lyrics. Use prompt/style to describe instrumental mood, genre, arrangement, and structure.

                Copyright and style safety:
                - If the user asks to imitate a song, artist, band, idol group, or named work, extract high-level musical traits only.
                - Do not claim to copy melody, lyrics, vocal identity, arrangement, or a copyrighted recording.
                - Add an originality constraint such as "original melody and lyrics only" when the user references a real artist or song.

                Upload cover policy:
                - generationType=upload_cover requires referenceAudio.
                - If upload_cover is requested but no referenceAudio is present, ask the user to upload audio instead of calling the tool.
                - For upload_cover in simple mode, keep prompt short and describe transformation intent only.

                Defaults:
                - Use the tool field default model unless the user explicitly names a supported version.
                - Do not change model solely because the user asks for better quality.
                - Leave advanced sliders, persona fields, negativeTags, and vocalGender at defaults unless explicitly requested.

                Self-correction:
                - If a prior Suno error says the non-custom prompt is too long, retry with a shorter prompt under 500 characters or switch to customMode=true for long lyrics.
                - Do not repeat the same overlong non-custom prompt.
                """;
        executeSql("""
                INSERT INTO agent_skill_bundles (
                  skill_code, display_name, description, tool_codes_json, sop_rules,
                  when_to_use, when_not_to_use, field_policy_json, examples_json,
                  status, version, published_at, created_at, updated_at
                )
                SELECT
                  'music_generation',
                  '音乐生成',
                  '创作歌曲、纯音乐或上传音频翻唱；约束 Suno 非 custom prompt 长度、歌词模式、版权风格转写和参考音频要求。',
                  %s,
                  %s,
                  '用户请求生成歌曲、纯音乐、歌词成歌、音乐风格创作、Suno 音乐、上传音频翻唱或改编时使用。',
                  '用户请求图片、视频、文本问答、PPT、语音朗读或只是在查询已有任务结果时不要使用。',
                  '{}',
                  %s,
                  'PUBLISHED',
                  1,
                  CURRENT_TIMESTAMP,
                  CURRENT_TIMESTAMP,
                  CURRENT_TIMESTAMP
                WHERE NOT EXISTS (
                  SELECT 1 FROM agent_skill_bundles WHERE skill_code = 'music_generation'
                )
                """.formatted(sqlNullableString(toolCodes), sqlNullableString(sop), sqlNullableString(examples)));
    }

    private void seedModelProviderMetadata() {
        modelProviderRegistry.listAll().forEach(provider -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM model_provider_metadata WHERE provider_code = ?",
                    Integer.class,
                    provider.code()
            );
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO model_provider_metadata(provider_code, label, capabilities_json, default_base_url, default_model,
                                                        billing_default, provider_protocol, vendor_kind, upstream_vendor,
                                                        test_strategy, worker_ready, adapter_installed, adapter_key,
                                                        metadata_version, auth_schema_json, model_param_schema_json,
                                                        description, enabled)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """,
                    provider.code(),
                    provider.label(),
                    toJson(provider.capabilities()),
                    provider.defaultBaseUrl(),
                    provider.defaultModel(),
                    provider.billingDefault(),
                    provider.providerProtocol(),
                    provider.vendorKind(),
                    provider.upstreamVendor(),
                    provider.testStrategy(),
                    provider.workerReady() ? 1 : 0,
                    provider.adapterInstalled() ? 1 : 0,
                    provider.adapterKey() == null || provider.adapterKey().isBlank() ? provider.code() : provider.adapterKey(),
                    provider.metadataVersion() == null || provider.metadataVersion().isBlank() ? "manifest" : provider.metadataVersion(),
                    provider.authSchemaJson(),
                    provider.modelParamSchemaJson(),
                    provider.description()
            );
        });
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private void ensureSchemaCompatibility() {
        ensureTable("admin_operation_logs", """
                CREATE TABLE admin_operation_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  admin_id BIGINT NOT NULL,
                  operation_type VARCHAR(64) NOT NULL,
                  target_type VARCHAR(64) NOT NULL,
                  target_id BIGINT NULL,
                  content_json TEXT NULL,
                  reason VARCHAR(512) NULL,
                  ip_address VARCHAR(64) NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureIndex("admin_operation_logs", "idx_admin_operation_logs_created_at", "CREATE INDEX idx_admin_operation_logs_created_at ON admin_operation_logs(created_at)");
        ensureIndex("admin_operation_logs", "idx_admin_operation_logs_admin_id", "CREATE INDEX idx_admin_operation_logs_admin_id ON admin_operation_logs(admin_id)");
        ensureTable("auth_security_events", """
                CREATE TABLE auth_security_events (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  event_type VARCHAR(64) NOT NULL,
                  result VARCHAR(16) NOT NULL,
                  method VARCHAR(32) NULL,
                  user_type VARCHAR(16) NULL,
                  user_id BIGINT NULL,
                  account_hash VARCHAR(64) NULL,
                  account_masked VARCHAR(64) NULL,
                  failure_reason VARCHAR(128) NULL,
                  ip_address VARCHAR(64) NULL,
                  user_agent VARCHAR(512) NULL,
                  trace_id VARCHAR(64) NULL,
                  country VARCHAR(64) NULL,
                  region VARCHAR(64) NULL,
                  city VARCHAR(64) NULL,
                  latitude DECIMAL(10,6) NULL,
                  longitude DECIMAL(10,6) NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureIndex("auth_security_events", "idx_auth_security_events_created_at", "CREATE INDEX idx_auth_security_events_created_at ON auth_security_events(created_at)");
        ensureIndex("auth_security_events", "idx_auth_security_events_type_result", "CREATE INDEX idx_auth_security_events_type_result ON auth_security_events(event_type, result, created_at)");
        ensureIndex("auth_security_events", "idx_auth_security_events_geo", "CREATE INDEX idx_auth_security_events_geo ON auth_security_events(latitude, longitude, created_at)");
        ensureIndex("auth_security_events", "idx_auth_security_events_ip_time", "CREATE INDEX idx_auth_security_events_ip_time ON auth_security_events(ip_address, created_at)");
        ensureColumn("users", "avatar_url", "ALTER TABLE users ADD COLUMN avatar_url VARCHAR(512) NULL");
        ensureColumn("users", "bio", "ALTER TABLE users ADD COLUMN bio VARCHAR(280) NULL");
        ensureColumn("users", "auto_publish_assets", "ALTER TABLE users ADD COLUMN auto_publish_assets TINYINT NOT NULL DEFAULT 1");
        ensureColumn("users", "prompt_public_by_default", "ALTER TABLE users ADD COLUMN prompt_public_by_default TINYINT NOT NULL DEFAULT 0");
        ensureColumn("users", "public_code", "ALTER TABLE users ADD COLUMN public_code CHAR(5) NULL");
        ensureIndex("users", "uk_users_public_code", "CREATE UNIQUE INDEX uk_users_public_code ON users(public_code)");
        ensureColumn("users", "referral_code", "ALTER TABLE users ADD COLUMN referral_code CHAR(6) NULL");
        ensureIndex("users", "uk_users_referral_code", "CREATE UNIQUE INDEX uk_users_referral_code ON users(referral_code)");
        ensureColumn("ai_tools", "model_config_id", "ALTER TABLE ai_tools ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("ai_tools", "tool_type", "ALTER TABLE ai_tools ADD COLUMN tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION'");
        ensureColumn("ai_tools", "input_modality", "ALTER TABLE ai_tools ADD COLUMN input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "output_modality", "ALTER TABLE ai_tools ADD COLUMN output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "config_note", "ALTER TABLE ai_tools ADD COLUMN config_note TEXT NULL");
        ensureColumn("ai_tools", "required_model_capabilities", "ALTER TABLE ai_tools ADD COLUMN required_model_capabilities TEXT NULL");
        executeSqlIgnore("ALTER TABLE ai_tools MODIFY COLUMN category_id BIGINT NULL");
        ensureColumn("tool_field_schema_items", "execution_required", "ALTER TABLE tool_field_schema_items ADD COLUMN execution_required TINYINT NOT NULL DEFAULT 0");
        ensureColumn("tool_field_schema_items", "user_required", "ALTER TABLE tool_field_schema_items ADD COLUMN user_required TINYINT NOT NULL DEFAULT 0");
        ensureColumn("tool_field_schema_items", "default_value", "ALTER TABLE tool_field_schema_items ADD COLUMN default_value VARCHAR(512) NULL");
        ensureColumn("tool_field_schema_items", "agent_fill_strategy", "ALTER TABLE tool_field_schema_items ADD COLUMN agent_fill_strategy VARCHAR(32) NOT NULL DEFAULT 'default'");
        ensureColumn("tool_field_schema_items", "risk_level", "ALTER TABLE tool_field_schema_items ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW'");
        executeSql("""
                UPDATE tool_field_schema_items
                SET execution_required = required,
                    user_required = required,
                    agent_fill_strategy = CASE WHEN required = 1 THEN 'ask_user' ELSE 'default' END,
                    risk_level = 'LOW'
                WHERE agent_fill_strategy IS NULL OR agent_fill_strategy = ''
                """);
        ensureColumn("ai_tasks", "user_deleted", "ALTER TABLE ai_tasks ADD COLUMN user_deleted TINYINT NOT NULL DEFAULT 0");
        ensureColumn("ai_tasks", "user_deleted_at", "ALTER TABLE ai_tasks ADD COLUMN user_deleted_at DATETIME NULL");
        ensureColumn("ai_tasks", "model_config_id", "ALTER TABLE ai_tasks ADD COLUMN model_config_id BIGINT NULL");
        ensureIndex("ai_tasks", "idx_tasks_model_config", "CREATE INDEX idx_tasks_model_config ON ai_tasks(model_config_id)");
        ensureColumn("ai_tasks", "selected_model_config_id", "ALTER TABLE ai_tasks ADD COLUMN selected_model_config_id BIGINT NULL");
        ensureColumn("ai_tasks", "selected_vendor_account_id", "ALTER TABLE ai_tasks ADD COLUMN selected_vendor_account_id BIGINT NULL");
        ensureColumn("ai_tasks", "current_route_attempt_id", "ALTER TABLE ai_tasks ADD COLUMN current_route_attempt_id BIGINT NULL");
        ensureIndex("ai_tasks", "idx_ai_tasks_selected_route", "CREATE INDEX idx_ai_tasks_selected_route ON ai_tasks(selected_vendor_account_id, status, id)");
        ensureColumn("ai_tasks", "model_snapshot_json", "ALTER TABLE ai_tasks ADD COLUMN model_snapshot_json TEXT NULL");
        ensureColumn("ai_tasks", "user_message", "ALTER TABLE ai_tasks ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("ai_tasks", "developer_message", "ALTER TABLE ai_tasks ADD COLUMN developer_message TEXT NULL");
        ensureColumn("ai_tasks", "failure_trace_id", "ALTER TABLE ai_tasks ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureColumn("ai_tasks", "provider_error_code", "ALTER TABLE ai_tasks ADD COLUMN provider_error_code VARCHAR(128) NULL");
        ensureColumn("ai_tasks", "provider_request_id", "ALTER TABLE ai_tasks ADD COLUMN provider_request_id VARCHAR(128) NULL");
        ensureIndex("ai_tasks", "idx_ai_tasks_failure_trace", "CREATE INDEX idx_ai_tasks_failure_trace ON ai_tasks(failure_trace_id)");
        ensureColumn("ai_task_logs", "user_message", "ALTER TABLE ai_task_logs ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("ai_task_logs", "developer_message", "ALTER TABLE ai_task_logs ADD COLUMN developer_message TEXT NULL");
        ensureColumn("ai_task_logs", "failure_trace_id", "ALTER TABLE ai_task_logs ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("ai_task_logs", "idx_ai_task_logs_failure_trace", "CREATE INDEX idx_ai_task_logs_failure_trace ON ai_task_logs(failure_trace_id)");
        ensureColumn("ai_tasks", "claimed_by", "ALTER TABLE ai_tasks ADD COLUMN claimed_by VARCHAR(128) NULL");
        ensureColumn("ai_tasks", "claim_token", "ALTER TABLE ai_tasks ADD COLUMN claim_token VARCHAR(128) NULL");
        ensureColumn("ai_tasks", "lease_until", "ALTER TABLE ai_tasks ADD COLUMN lease_until DATETIME NULL");
        ensureColumn("ai_tasks", "claimed_at", "ALTER TABLE ai_tasks ADD COLUMN claimed_at DATETIME NULL");
        ensureColumn("ai_tasks", "lease_renewed_at", "ALTER TABLE ai_tasks ADD COLUMN lease_renewed_at DATETIME NULL");
        ensureColumn("ai_tasks", "execution_attempt", "ALTER TABLE ai_tasks ADD COLUMN execution_attempt INT NOT NULL DEFAULT 0");
        ensureIndex("ai_tasks", "idx_tasks_lease", "CREATE INDEX idx_tasks_lease ON ai_tasks(status, lease_until, id)");
        ensureIndex("ai_tasks", "idx_tasks_claim_token", "CREATE INDEX idx_tasks_claim_token ON ai_tasks(claim_token)");
        ensureTable("user_upload_assets", """
                CREATE TABLE user_upload_assets (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  file_id VARCHAR(64) NOT NULL,
                  asset_kind VARCHAR(16) NOT NULL DEFAULT 'file',
                  original_filename VARCHAR(255) NOT NULL,
                  content_type VARCHAR(128) NULL,
                  file_size BIGINT NULL,
                  url VARCHAR(1024) NOT NULL,
                  storage_path VARCHAR(1024) NULL,
                  history_visible TINYINT NOT NULL DEFAULT 1,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_user_upload_assets_user_kind (user_id, asset_kind, status, id),
                  KEY idx_user_upload_assets_user_url (user_id, url(255))
                )
                """);
        ensureColumn("user_upload_assets", "history_visible", "ALTER TABLE user_upload_assets ADD COLUMN history_visible TINYINT NOT NULL DEFAULT 1");
        ensureColumn("agent_model_configs", "display_name", "ALTER TABLE agent_model_configs ADD COLUMN display_name VARCHAR(128) NULL");
        ensureColumn("agent_model_configs", "config_code", "ALTER TABLE agent_model_configs ADD COLUMN config_code VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "console_url", "ALTER TABLE agent_model_configs ADD COLUMN console_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "balance_url", "ALTER TABLE agent_model_configs ADD COLUMN balance_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "docs_url", "ALTER TABLE agent_model_configs ADD COLUMN docs_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "extra_auth_json", "ALTER TABLE agent_model_configs ADD COLUMN extra_auth_json TEXT NULL");
        ensureColumn("agent_model_configs", "execution_task", "ALTER TABLE agent_model_configs ADD COLUMN execution_task VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "execution_options_json", "ALTER TABLE agent_model_configs ADD COLUMN execution_options_json TEXT NULL");
        ensureColumn("agent_model_configs", "request_schema_json", "ALTER TABLE agent_model_configs ADD COLUMN request_schema_json MEDIUMTEXT NULL");
        ensureColumn("agent_model_configs", "request_mapping_json", "ALTER TABLE agent_model_configs ADD COLUMN request_mapping_json MEDIUMTEXT NULL");
        ensureColumn("agent_model_configs", "response_mapping_json", "ALTER TABLE agent_model_configs ADD COLUMN response_mapping_json MEDIUMTEXT NULL");
        ensureColumn("agent_model_configs", "api_contract_version", "ALTER TABLE agent_model_configs ADD COLUMN api_contract_version VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "contract_status", "ALTER TABLE agent_model_configs ADD COLUMN contract_status VARCHAR(32) NOT NULL DEFAULT 'DOCS_PENDING'");
        ensureColumn("agent_model_configs", "contract_verified_at", "ALTER TABLE agent_model_configs ADD COLUMN contract_verified_at DATETIME NULL");
        ensureTable("tool_model_bindings", """
                CREATE TABLE tool_model_bindings (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  tool_id BIGINT NOT NULL,
                  model_config_id BIGINT NOT NULL,
                  is_default TINYINT NOT NULL DEFAULT 0,
                  sort_order INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_tool_model_binding(tool_id, model_config_id),
                  KEY idx_tool_model_binding_default(tool_id, is_default, sort_order, id),
                  KEY idx_tool_model_binding_model(model_config_id, tool_id)
                )
                """);
        executeSql("""
                INSERT IGNORE INTO tool_model_bindings(tool_id, model_config_id, is_default, sort_order)
                SELECT id, model_config_id, 1, 0
                FROM ai_tools
                WHERE is_deleted = 0
                  AND model_config_id IS NOT NULL
                """);
        executeSql("""
                UPDATE agent_model_configs
                SET execution_task = CASE
                    WHEN config_code = 'kling-gateway-omni-video' THEN 'omni_video'
                    WHEN config_code = 'kling-gateway-omni-image' THEN 'omni_image'
                    WHEN config_code LIKE '%multi-image%' OR config_code LIKE '%multi_image%' THEN 'multi_image2video'
                    WHEN config_code LIKE '%motion%' THEN 'motion_control'
                    WHEN config_code LIKE '%image-to-video%' OR config_code LIKE '%image2video%' THEN 'image2video'
                    WHEN provider = 'kling_video' AND capabilities LIKE '%IMAGE_GENERATION%' THEN 'image_generation'
                    WHEN provider = 'kling_video' AND capabilities LIKE '%VIDEO_GENERATION%' THEN 'text2video'
                    ELSE execution_task
                END
                WHERE is_deleted = 0
                  AND provider = 'kling_video'
                  AND (execution_task IS NULL OR execution_task = '')
                """);
        executeSqlIgnore("""
                UPDATE agent_model_configs
                SET execution_task = LOWER(REPLACE(
                    REPLACE(CAST(JSON_EXTRACT(extra_auth_json, '$.apiTask') AS CHAR), '"', ''),
                    '-', '_'))
                WHERE is_deleted = 0
                  AND (execution_task IS NULL OR execution_task = '')
                  AND extra_auth_json IS NOT NULL
                  AND JSON_VALID(extra_auth_json)
                  AND JSON_EXTRACT(extra_auth_json, '$.apiTask') IS NOT NULL
                """);
        executeSqlIgnore("""
                UPDATE agent_model_configs
                SET execution_options_json = JSON_OBJECT(
                    'createPath', REPLACE(CAST(JSON_EXTRACT(extra_auth_json, '$.createPath') AS CHAR), '"', ''),
                    'resultPath', REPLACE(CAST(JSON_EXTRACT(extra_auth_json, '$.resultPath') AS CHAR), '"', '')
                )
                WHERE is_deleted = 0
                  AND (execution_options_json IS NULL OR execution_options_json = '')
                  AND extra_auth_json IS NOT NULL
                  AND JSON_VALID(extra_auth_json)
                  AND JSON_EXTRACT(extra_auth_json, '$.createPath') IS NOT NULL
                  AND JSON_EXTRACT(extra_auth_json, '$.resultPath') IS NOT NULL
                """);
        ensureColumn("agent_model_configs", "input_token_price_per_1k", "ALTER TABLE agent_model_configs ADD COLUMN input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "output_token_price_per_1k", "ALTER TABLE agent_model_configs ADD COLUMN output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "input_token_price_per_1m", "ALTER TABLE agent_model_configs ADD COLUMN input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "output_token_price_per_1m", "ALTER TABLE agent_model_configs ADD COLUMN output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "billing_unit", "ALTER TABLE agent_model_configs ADD COLUMN billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M'");
        ensureColumn("agent_model_configs", "unit_price", "ALTER TABLE agent_model_configs ADD COLUMN unit_price DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "capabilities", "ALTER TABLE agent_model_configs ADD COLUMN capabilities TEXT NULL");
        ensureColumn("agent_model_configs", "agent_enabled", "ALTER TABLE agent_model_configs ADD COLUMN agent_enabled TINYINT NOT NULL DEFAULT 1");
        ensureColumn("agent_model_configs", "last_test_success", "ALTER TABLE agent_model_configs ADD COLUMN last_test_success TINYINT NULL");
        ensureColumn("agent_model_configs", "last_test_message", "ALTER TABLE agent_model_configs ADD COLUMN last_test_message VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "last_test_at", "ALTER TABLE agent_model_configs ADD COLUMN last_test_at DATETIME NULL");
        ensureIndex(
                "agent_model_configs",
                "idx_agent_model_configs_agent_enabled",
                "CREATE INDEX idx_agent_model_configs_agent_enabled ON agent_model_configs(agent_enabled, enabled, is_deleted, is_default, id)"
        );
        executeSql("""
                UPDATE agent_model_configs
                SET input_token_price_per_1m = input_token_price_per_1k * 1000
                WHERE input_token_price_per_1m = 0 AND input_token_price_per_1k > 0
                """);
        executeSql("""
                UPDATE agent_model_configs
                SET output_token_price_per_1m = output_token_price_per_1k * 1000
                WHERE output_token_price_per_1m = 0 AND output_token_price_per_1k > 0
                """);
        executeSql("""
                UPDATE agent_model_configs
                SET billing_unit = 'IMAGE_TOKEN',
                    input_token_price_per_1m = CASE WHEN input_token_price_per_1m > 0 THEN input_token_price_per_1m ELSE 8 END,
                    output_token_price_per_1m = CASE WHEN output_token_price_per_1m > 0 THEN output_token_price_per_1m ELSE 30 END
                WHERE is_deleted = 0
                  AND (
                    LOWER(model_name) LIKE '%gpt-image%'
                    OR LOWER(display_name) LIKE '%image2%'
                    OR LOWER(display_name) LIKE '%gpt-image%'
                  )
                  AND (
                    billing_unit IS NULL OR billing_unit = '' OR billing_unit = 'TOKEN_PER_M'
                    OR (billing_unit = 'IMAGE_TOKEN' AND input_token_price_per_1m = 0 AND output_token_price_per_1m = 0)
                  )
                """);
        executeSql("""
                UPDATE agent_model_configs
                SET enabled = 1
                WHERE is_deleted = 0
                  AND enabled = 0
                  AND EXISTS (
                    SELECT 1 FROM ai_tools t
                    WHERE t.model_config_id = agent_model_configs.id AND t.status = 'ONLINE'
                  )
                  AND (
                    LOWER(model_name) LIKE '%gpt-image%'
                    OR LOWER(display_name) LIKE '%image2%'
                    OR LOWER(display_name) LIKE '%gpt-image%'
                  )
                """);
        ensureColumn("agent_model_configs", "is_default", "ALTER TABLE agent_model_configs ADD COLUMN is_default TINYINT NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "is_deleted", "ALTER TABLE agent_model_configs ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "vendor_account_id",
                "ALTER TABLE agent_model_configs ADD COLUMN vendor_account_id BIGINT NULL COMMENT '所属厂商账户' AFTER id");
        ensureIndex(
                "agent_model_configs",
                "idx_agent_model_configs_vendor_account",
                "CREATE INDEX idx_agent_model_configs_vendor_account ON agent_model_configs(vendor_account_id, enabled, is_deleted)"
        );
        ensureTable("model_vendors", """
                CREATE TABLE model_vendors (
                  vendor_code VARCHAR(64) PRIMARY KEY,
                  vendor_label VARCHAR(128) NOT NULL,
                  icon_asset VARCHAR(128) NOT NULL,
                  sort_order INT NOT NULL DEFAULT 0,
                  enabled TINYINT NOT NULL DEFAULT 1,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  KEY idx_model_vendors_enabled (enabled, sort_order)
                )
                """);
        executeSql("""
                INSERT INTO model_vendors(vendor_code, vendor_label, icon_asset, sort_order, enabled)
                VALUES
                  ('qwen', '阿里云百炼', 'qwen', 45, 1),
                  ('suno', 'Suno', 'suno', 55, 1),
                  ('agnes', 'Agnes AI', 'agnes', 80, 1)
                ON DUPLICATE KEY UPDATE
                  vendor_label = VALUES(vendor_label),
                  icon_asset = VALUES(icon_asset),
                  sort_order = VALUES(sort_order),
                  enabled = VALUES(enabled),
                  updated_at = CURRENT_TIMESTAMP
                """);
        ensureTable("model_account_routing_pools", """
                CREATE TABLE model_account_routing_pools (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  vendor_code VARCHAR(64) NOT NULL,
                  pool_name VARCHAR(128) NOT NULL,
                  pool_key VARCHAR(128) NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_model_account_routing_pool_vendor_key(vendor_code, pool_key)
                )
                """);
        ensureTable("model_vendor_accounts", """
                CREATE TABLE model_vendor_accounts (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  vendor_code VARCHAR(64) NOT NULL,
                  account_name VARCHAR(128) NOT NULL DEFAULT '默认账户',
                  base_url VARCHAR(512) NULL,
                  api_key VARCHAR(1024) NULL,
                  extra_auth_json TEXT NULL,
                  console_url VARCHAR(512) NULL,
                  balance_url VARCHAR(512) NULL,
                  console_cookie TEXT NULL,
                  console_cookie_status VARCHAR(20) NULL DEFAULT 'UNKNOWN',
                  balance_query_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
                  balance_amount DECIMAL(18,4) NULL,
                  balance_currency VARCHAR(8) NULL DEFAULT 'CNY',
                  balance_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  balance_low_threshold DECIMAL(18,4) NULL,
                  balance_updated_at DATETIME NULL,
                  balance_error_message VARCHAR(512) NULL,
                  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  health_message VARCHAR(512) NULL,
                  health_checked_at DATETIME NULL,
                  load_balance_enabled TINYINT NOT NULL DEFAULT 0,
                  load_balance_weight INT NOT NULL DEFAULT 100,
                  routing_pool_id BIGINT NULL,
                  enabled TINYINT NOT NULL DEFAULT 1,
                  is_deleted TINYINT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureColumn("model_vendor_accounts", "console_cookie", "ALTER TABLE model_vendor_accounts ADD COLUMN console_cookie TEXT NULL AFTER balance_url");
        ensureColumn("model_vendor_accounts", "console_cookie_status", "ALTER TABLE model_vendor_accounts ADD COLUMN console_cookie_status VARCHAR(20) NULL DEFAULT 'UNKNOWN' AFTER console_cookie");
        ensureColumn("model_vendor_accounts", "health_message", "ALTER TABLE model_vendor_accounts ADD COLUMN health_message VARCHAR(512) NULL AFTER health_status");
        ensureColumn("model_vendor_accounts", "health_checked_at", "ALTER TABLE model_vendor_accounts ADD COLUMN health_checked_at DATETIME NULL AFTER health_message");
        ensureColumn("model_vendor_accounts", "load_balance_enabled", "ALTER TABLE model_vendor_accounts ADD COLUMN load_balance_enabled TINYINT NOT NULL DEFAULT 0 AFTER health_checked_at");
        ensureColumn("model_vendor_accounts", "load_balance_weight", "ALTER TABLE model_vendor_accounts ADD COLUMN load_balance_weight INT NOT NULL DEFAULT 100 AFTER load_balance_enabled");
        ensureColumn("model_vendor_accounts", "routing_pool_id", "ALTER TABLE model_vendor_accounts ADD COLUMN routing_pool_id BIGINT NULL AFTER load_balance_weight");
        ensureIndex(
                "model_vendor_accounts",
                "idx_model_vendor_accounts_routing_pool",
                "CREATE INDEX idx_model_vendor_accounts_routing_pool ON model_vendor_accounts(routing_pool_id, enabled, is_deleted)"
        );
        ensureColumn("agent_model_configs", "routing_pool_id", "ALTER TABLE agent_model_configs ADD COLUMN routing_pool_id BIGINT NULL AFTER vendor_account_id");
        ensureIndex(
                "agent_model_configs",
                "idx_agent_model_configs_routing_pool",
                "CREATE INDEX idx_agent_model_configs_routing_pool ON agent_model_configs(routing_pool_id, enabled, is_deleted)"
        );
        ensureTable("task_model_route_attempts", """
                CREATE TABLE task_model_route_attempts (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  task_id BIGINT NOT NULL,
                  attempt_no INT NOT NULL,
                  model_config_id BIGINT NOT NULL,
                  vendor_account_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  delivery_state VARCHAR(32) NULL,
                  failure_stage VARCHAR(64) NULL,
                  error_code VARCHAR(64) NULL,
                  error_message TEXT NULL,
                  user_message VARCHAR(255) NULL,
                  developer_message TEXT NULL,
                  failure_trace_id VARCHAR(64) NULL,
                  provider_error_code VARCHAR(128) NULL,
                  provider_request_id VARCHAR(128) NULL,
                  provider_charged TINYINT NULL,
                  retry_after_seconds INT NULL,
                  claim_token VARCHAR(128) NULL,
                  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  finished_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_task_model_route_attempt(task_id, attempt_no),
                  KEY idx_task_model_route_attempt_active(vendor_account_id, status, task_id),
                  KEY idx_task_model_route_attempt_task(task_id, status),
                  KEY idx_task_model_route_attempt_failure_trace(failure_trace_id)
                )
                """);
        ensureColumn("task_model_route_attempts", "user_message", "ALTER TABLE task_model_route_attempts ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("task_model_route_attempts", "developer_message", "ALTER TABLE task_model_route_attempts ADD COLUMN developer_message TEXT NULL");
        ensureColumn("task_model_route_attempts", "failure_trace_id", "ALTER TABLE task_model_route_attempts ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("task_model_route_attempts", "idx_task_model_route_attempt_failure_trace", "CREATE INDEX idx_task_model_route_attempt_failure_trace ON task_model_route_attempts(failure_trace_id)");
        ensureTable("account_model_route_state", """
                CREATE TABLE account_model_route_state (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  vendor_account_id BIGINT NOT NULL,
                  model_config_id BIGINT NOT NULL,
                  in_flight_count INT NOT NULL DEFAULT 0,
                  circuit_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
                  consecutive_failures INT NOT NULL DEFAULT 0,
                  cooldown_until DATETIME NULL,
                  last_selected_at DATETIME NULL,
                  version INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_account_model_route_state_model(model_config_id),
                  KEY idx_account_model_route_state_account(vendor_account_id, circuit_status),
                  KEY idx_account_model_route_state_cooldown(circuit_status, cooldown_until)
                )
                """);
        executeSql("""
                UPDATE model_vendor_accounts
                SET balance_currency = 'USD',
                    updated_at = CURRENT_TIMESTAMP
                WHERE is_deleted = 0
                  AND LOWER(COALESCE(base_url, '')) LIKE '%ofox.ai%'
                  AND (balance_currency IS NULL OR balance_currency = '' OR UPPER(balance_currency) = 'CNY')
                """);
        executeSql("""
                UPDATE agent_model_configs
                SET input_token_price_per_1m = input_token_price_per_1m * 7.2,
                    output_token_price_per_1m = output_token_price_per_1m * 7.2,
                    input_token_price_per_1k = input_token_price_per_1k * 7.2,
                    output_token_price_per_1k = output_token_price_per_1k * 7.2,
                    unit_price = unit_price * 7.2,
                    updated_at = CURRENT_TIMESTAMP
                WHERE is_deleted = 0
                  AND billing_unit = 'IMAGE_TOKEN'
                  AND input_token_price_per_1m = 8
                  AND output_token_price_per_1m = 30
                  AND (
                    LOWER(COALESCE(model_name, '')) LIKE '%gpt-image%'
                    OR LOWER(COALESCE(display_name, '')) LIKE '%image2%'
                    OR LOWER(COALESCE(display_name, '')) LIKE '%gpt-image%'
                  )
                  AND (
                    LOWER(COALESCE(base_url, '')) LIKE '%ofox.ai%'
                    OR EXISTS (
                      SELECT 1 FROM model_vendor_accounts account
                      WHERE account.id = agent_model_configs.vendor_account_id
                        AND account.is_deleted = 0
                        AND UPPER(COALESCE(account.balance_currency, '')) = 'USD'
                        AND (
                          LOWER(COALESCE(account.base_url, '')) LIKE '%ofox.ai%'
                          OR LOWER(COALESCE(account.vendor_code, '')) = 'openai'
                        )
                    )
                  )
                """);
        executeSql("""
                UPDATE model_vendor_accounts
                SET enabled = 0,
                    is_deleted = 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE vendor_code = 'aliyun_bailian'
                """);
        executeSql("DELETE FROM model_vendors WHERE vendor_code = 'aliyun_bailian'");
        ensureTable("model_provider_metadata", """
                CREATE TABLE model_provider_metadata (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  provider_code VARCHAR(64) NOT NULL UNIQUE,
                  label VARCHAR(128) NOT NULL,
                  capabilities_json TEXT NOT NULL,
                  default_base_url VARCHAR(512) NULL,
                  default_model VARCHAR(128) NULL,
                  billing_default VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
                  provider_protocol VARCHAR(64) NULL,
                  vendor_kind VARCHAR(64) NULL,
                  upstream_vendor VARCHAR(64) NULL,
                  test_strategy VARCHAR(32) NOT NULL DEFAULT 'accept_only',
                  worker_ready TINYINT NOT NULL DEFAULT 0,
                  adapter_installed TINYINT NOT NULL DEFAULT 0,
                  adapter_key VARCHAR(64) NULL,
                  metadata_version VARCHAR(64) NOT NULL DEFAULT 'db',
                  auth_schema_json TEXT NULL,
                  model_param_schema_json TEXT NULL,
                  description TEXT NULL,
                  enabled TINYINT NOT NULL DEFAULT 1,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureTable("gift_card_packages", """
                CREATE TABLE gift_card_packages (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  package_code VARCHAR(64) NOT NULL UNIQUE,
                  package_name VARCHAR(128) NOT NULL,
                  credits INT NOT NULL,
                  price_amount DECIMAL(18,2) NOT NULL,
                  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
                  card_theme VARCHAR(32) NOT NULL DEFAULT 'classic',
                  card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT',
                  required_member_tier VARCHAR(32) NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  sort_order INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureTable("gift_cards", """
                CREATE TABLE gift_cards (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  card_code VARCHAR(64) NOT NULL UNIQUE,
                  package_id BIGINT NOT NULL,
                  owner_user_id BIGINT NOT NULL,
                  original_user_id BIGINT NOT NULL,
                  credits INT NOT NULL,
                  card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT',
                  required_member_tier VARCHAR(32) NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'UNUSED',
                  recharge_order_id BIGINT NULL,
                  issuance_key VARCHAR(128) NULL,
                  issuance_operator_id BIGINT NULL,
                  issuance_reason VARCHAR(512) NULL,
                  redeemed_at DATETIME NULL,
                  gifted_from_user_id BIGINT NULL,
                  gifted_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_gift_cards_owner (owner_user_id, status),
                  KEY idx_gift_cards_code (card_code)
                )
                """);
        ensureColumn("gift_card_packages", "card_type", "ALTER TABLE gift_card_packages ADD COLUMN card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER card_theme");
        ensureColumn("gift_card_packages", "required_member_tier", "ALTER TABLE gift_card_packages ADD COLUMN required_member_tier VARCHAR(32) NULL AFTER card_type");
        ensureColumn("gift_cards", "card_type", "ALTER TABLE gift_cards ADD COLUMN card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER credits");
        ensureColumn("gift_cards", "required_member_tier", "ALTER TABLE gift_cards ADD COLUMN required_member_tier VARCHAR(32) NULL AFTER card_type");
        ensureColumn("credit_accounts", "membership_balance", "ALTER TABLE credit_accounts ADD COLUMN membership_balance INT NOT NULL DEFAULT 0 AFTER balance");
        ensureColumn("credit_accounts", "gift_balance", "ALTER TABLE credit_accounts ADD COLUMN gift_balance INT NOT NULL DEFAULT 0 AFTER membership_balance");
        ensureColumn("credit_accounts", "permanent_balance", "ALTER TABLE credit_accounts ADD COLUMN permanent_balance INT NOT NULL DEFAULT 0 AFTER balance");
        ensureColumn("credit_accounts", "permanent_frozen", "ALTER TABLE credit_accounts ADD COLUMN permanent_frozen INT NOT NULL DEFAULT 0 AFTER frozen");
        ensureColumn("credit_accounts", "membership_frozen", "ALTER TABLE credit_accounts ADD COLUMN membership_frozen INT NOT NULL DEFAULT 0 AFTER permanent_frozen");
        ensureColumn("credit_accounts", "gift_frozen", "ALTER TABLE credit_accounts ADD COLUMN gift_frozen INT NOT NULL DEFAULT 0 AFTER membership_frozen");
        ensureColumn("credit_accounts", "expired_membership_frozen", "ALTER TABLE credit_accounts ADD COLUMN expired_membership_frozen INT NOT NULL DEFAULT 0 AFTER gift_frozen");
        ensureColumn("credit_accounts", "total_expired", "ALTER TABLE credit_accounts ADD COLUMN total_expired INT NOT NULL DEFAULT 0 AFTER total_consumed");
        ensureColumn("credit_accounts", "bucket_schema_version", "ALTER TABLE credit_accounts ADD COLUMN bucket_schema_version INT NOT NULL DEFAULT 1 AFTER total_expired");
        assertNoPaidMembershipOrdersBeforeBucketMigration();
        executeSql("""
                UPDATE credit_accounts
                SET permanent_balance = CASE
                        WHEN membership_balance = 0 AND gift_balance = 0 THEN balance
                        ELSE membership_balance
                    END,
                    permanent_frozen = frozen,
                    membership_balance = 0,
                    membership_frozen = 0,
                    bucket_schema_version = 2
                WHERE bucket_schema_version < 2
                """);
        ensureTable("credit_recharge_packages", """
                CREATE TABLE credit_recharge_packages (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  package_code VARCHAR(64) NOT NULL UNIQUE,
                  package_name VARCHAR(128) NOT NULL,
                  credits INT NOT NULL,
                  price_amount DECIMAL(18,2) NOT NULL,
                  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
                  validity_days INT NOT NULL DEFAULT 0,
                  benefits_json TEXT,
                  recommended TINYINT NOT NULL DEFAULT 0,
                  sort_order INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureTable("credit_recharge_orders", """
                CREATE TABLE credit_recharge_orders (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_no VARCHAR(64) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  package_id BIGINT NULL,
                  credits INT NOT NULL,
                  price_amount DECIMAL(18,2) NOT NULL,
                  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
                  payment_channel VARCHAR(32) NOT NULL DEFAULT 'MOCK',
                  status VARCHAR(32) NOT NULL DEFAULT 'WAITING_PAYMENT',
                  status_reason VARCHAR(255),
                  pay_url TEXT,
                  qr_code_url VARCHAR(512),
                  external_trade_no VARCHAR(128),
                  idempotency_key VARCHAR(128),
                  paid_at DATETIME,
                  credited_at DATETIME,
                  closed_at DATETIME,
                  expires_at DATETIME NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        executeSqlIgnore("ALTER TABLE credit_recharge_orders MODIFY COLUMN pay_url TEXT NULL");
        executeSqlIgnore("ALTER TABLE credit_recharge_orders MODIFY COLUMN package_id BIGINT NULL");
        ensureColumn("credit_recharge_orders", "order_type", "ALTER TABLE credit_recharge_orders ADD COLUMN order_type VARCHAR(32) NOT NULL DEFAULT 'CREDITS'");
        ensureColumn("credit_recharge_orders", "gift_card_package_id", "ALTER TABLE credit_recharge_orders ADD COLUMN gift_card_package_id BIGINT NULL");
        ensureColumn("credit_recharge_orders", "request_fingerprint", "ALTER TABLE credit_recharge_orders ADD COLUMN request_fingerprint VARCHAR(64) NULL AFTER idempotency_key");
        ensureColumn("credit_recharge_orders", "package_code_snapshot", "ALTER TABLE credit_recharge_orders ADD COLUMN package_code_snapshot VARCHAR(64) NULL AFTER gift_card_package_id");
        ensureColumn("credit_recharge_orders", "validity_days_snapshot", "ALTER TABLE credit_recharge_orders ADD COLUMN validity_days_snapshot INT NULL AFTER package_code_snapshot");
        ensureColumn("gift_cards", "issuance_key", "ALTER TABLE gift_cards ADD COLUMN issuance_key VARCHAR(128) NULL AFTER recharge_order_id");
        ensureColumn("gift_cards", "issuance_operator_id", "ALTER TABLE gift_cards ADD COLUMN issuance_operator_id BIGINT NULL AFTER issuance_key");
        ensureColumn("gift_cards", "issuance_reason", "ALTER TABLE gift_cards ADD COLUMN issuance_reason VARCHAR(512) NULL AFTER issuance_operator_id");
        ensureIndex("gift_cards", "uk_gift_cards_issuance_key", "CREATE UNIQUE INDEX uk_gift_cards_issuance_key ON gift_cards(issuance_key)");
        executeSql("UPDATE gift_card_packages SET credits = 0, status = 'HIDDEN' WHERE package_code = 'admin_default'");
        ensureIndex(
                "credit_recharge_orders",
                "uk_recharge_user_idem",
                "CREATE UNIQUE INDEX uk_recharge_user_idem ON credit_recharge_orders(user_id, idempotency_key)"
        );
        ensureIndex(
                "credit_recharge_orders",
                "uk_recharge_external_trade_no",
                "CREATE UNIQUE INDEX uk_recharge_external_trade_no ON credit_recharge_orders(external_trade_no)"
        );
        ensureTable("user_memberships", """
                CREATE TABLE user_memberships (
                  user_id BIGINT PRIMARY KEY,
                  status VARCHAR(16) NOT NULL DEFAULT 'NONE',
                  package_id BIGINT NULL,
                  package_code VARCHAR(64) NULL,
                  order_id BIGINT NULL,
                  started_at DATETIME NULL,
                  expires_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_user_membership_order (order_id),
                  KEY idx_user_membership_status_expires (status, expires_at)
                )
                """);
        ensureTable("credit_recharge_order_items", """
                CREATE TABLE credit_recharge_order_items (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id BIGINT NOT NULL,
                  gift_card_package_id BIGINT NOT NULL,
                  quantity INT NOT NULL,
                  credits INT NOT NULL,
                  price_amount DECIMAL(18,2) NOT NULL,
                  item_type VARCHAR(32) NOT NULL DEFAULT 'GIFT_CARD',
                  card_type_snapshot VARCHAR(32) NOT NULL DEFAULT 'CREDIT',
                  required_member_tier_snapshot VARCHAR(32) NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_recharge_order_items_order (order_id)
                )
                """);
        ensureColumn("credit_recharge_order_items", "card_type_snapshot", "ALTER TABLE credit_recharge_order_items ADD COLUMN card_type_snapshot VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER item_type");
        ensureColumn("credit_recharge_order_items", "required_member_tier_snapshot", "ALTER TABLE credit_recharge_order_items ADD COLUMN required_member_tier_snapshot VARCHAR(32) NULL AFTER card_type_snapshot");
        ensureTable("user_referrals", """
                CREATE TABLE user_referrals (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  inviter_user_id BIGINT NOT NULL,
                  invitee_user_id BIGINT NOT NULL,
                  invite_code VARCHAR(64) NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_user_referrals_invitee (invitee_user_id),
                  KEY idx_user_referrals_inviter (inviter_user_id, created_at)
                )
                """);
        ensureTable("referral_rewards", """
                CREATE TABLE referral_rewards (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  referral_id BIGINT NOT NULL,
                  inviter_user_id BIGINT NOT NULL,
                  invitee_user_id BIGINT NOT NULL,
                  recharge_order_id BIGINT NOT NULL,
                  reward_credits INT NOT NULL,
                  reward_rate DECIMAL(10,4) NOT NULL DEFAULT 0.1000,
                  status VARCHAR(32) NOT NULL DEFAULT 'CREDITED',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_referral_rewards_order (recharge_order_id),
                  KEY idx_referral_rewards_inviter (inviter_user_id, created_at)
                )
                """);
        ensureTable("referral_registration_rewards", """
                CREATE TABLE referral_registration_rewards (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  referral_id BIGINT NOT NULL,
                  beneficiary_user_id BIGINT NOT NULL,
                  beneficiary_role VARCHAR(16) NOT NULL,
                  reward_credits INT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'CREDITED',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_referral_registration_reward_role (referral_id, beneficiary_role),
                  KEY idx_referral_registration_reward_user (beneficiary_user_id, created_at)
                )
                """);
        executeSql("""
                UPDATE credit_recharge_packages
                SET status = 'INACTIVE', updated_at = NOW()
                WHERE package_code = 'test_1000'
                  AND status = 'ACTIVE'
                """);
        ensureTable("pricing_margins", """
                CREATE TABLE pricing_margins (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
                  scope_ref BIGINT NOT NULL DEFAULT 0,
                  markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 1.2000,
                  min_credits INT NOT NULL DEFAULT 0,
                  image_estimate_input_tokens INT NULL,
                  image_estimate_output_tokens INT NULL,
                  token_estimate_input_tokens INT NULL,
                  token_estimate_output_tokens INT NULL,
                  enabled TINYINT NOT NULL DEFAULT 1,
                  remark VARCHAR(255),
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_pricing_margin_scope (scope_type, scope_ref),
                  KEY idx_pricing_margin_lookup (scope_type, scope_ref, enabled)
                )
                """);
        ensureColumn("pricing_margins", "image_estimate_input_tokens", "ALTER TABLE pricing_margins ADD COLUMN image_estimate_input_tokens INT NULL AFTER min_credits");
        ensureColumn("pricing_margins", "image_estimate_output_tokens", "ALTER TABLE pricing_margins ADD COLUMN image_estimate_output_tokens INT NULL AFTER image_estimate_input_tokens");
        ensureColumn("pricing_margins", "token_estimate_input_tokens", "ALTER TABLE pricing_margins ADD COLUMN token_estimate_input_tokens INT NULL AFTER image_estimate_output_tokens");
        ensureColumn("pricing_margins", "token_estimate_output_tokens", "ALTER TABLE pricing_margins ADD COLUMN token_estimate_output_tokens INT NULL AFTER token_estimate_input_tokens");
        ensureTable("pricing_rules", """
                CREATE TABLE pricing_rules (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  scope_type VARCHAR(16) NOT NULL DEFAULT 'MODEL',
                  scope_ref BIGINT NOT NULL DEFAULT 0,
                  param_key VARCHAR(64) NOT NULL,
                  rule_type VARCHAR(16) NOT NULL DEFAULT 'MULTIPLIER',
                  match_op VARCHAR(8) NOT NULL DEFAULT 'EQ',
                  match_value VARCHAR(64),
                  factor DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
                  extra_credits INT NOT NULL DEFAULT 0,
                  priority INT NOT NULL DEFAULT 100,
                  enabled TINYINT NOT NULL DEFAULT 1,
                  remark VARCHAR(255),
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  KEY idx_pricing_rules_scope (scope_type, scope_ref, enabled, priority)
                )
                """);
        executeSqlIgnore("""
                INSERT INTO pricing_margins (scope_type, scope_ref, markup_ratio, min_credits, enabled, remark)
                SELECT 'GLOBAL', 0, 1.2000, 0, 1, '默认全局加价 20%'
                WHERE NOT EXISTS (
                  SELECT 1 FROM pricing_margins WHERE scope_type = 'GLOBAL' AND scope_ref = 0
                )
                """);
        ensureTable("billing_usage_logs", """
                CREATE TABLE billing_usage_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  source_type VARCHAR(32) NOT NULL,
                  source_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  model_config_id BIGINT,
                  provider VARCHAR(64),
                  model_name VARCHAR(128),
                  prompt_tokens INT NOT NULL DEFAULT 0,
                  completion_tokens INT NOT NULL DEFAULT 0,
                  total_tokens INT NOT NULL DEFAULT 0,
                  input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
                  output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0,
                  input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
                  output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0,
                  billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M',
                  billable_units INT NOT NULL DEFAULT 0,
                  unit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
                  cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
                  charged_credits INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_billing_usage_created (created_at),
                  KEY idx_billing_usage_user (user_id),
                  KEY idx_billing_usage_source (source_type, source_id)
                )
                """);
        ensureColumn("billing_usage_logs", "input_token_price_per_1m", "ALTER TABLE billing_usage_logs ADD COLUMN input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("billing_usage_logs", "output_token_price_per_1m", "ALTER TABLE billing_usage_logs ADD COLUMN output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("billing_usage_logs", "billing_unit", "ALTER TABLE billing_usage_logs ADD COLUMN billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M'");
        ensureColumn("billing_usage_logs", "billable_units", "ALTER TABLE billing_usage_logs ADD COLUMN billable_units INT NOT NULL DEFAULT 0");
        ensureColumn("billing_usage_logs", "unit_price", "ALTER TABLE billing_usage_logs ADD COLUMN unit_price DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("billing_usage_logs", "vendor_cost_amount", "ALTER TABLE billing_usage_logs ADD COLUMN vendor_cost_amount DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER cost_amount");
        ensureColumn("billing_usage_logs", "provider_cost_currency", "ALTER TABLE billing_usage_logs ADD COLUMN provider_cost_currency VARCHAR(8) NOT NULL DEFAULT 'CNY' AFTER vendor_cost_amount");
        ensureColumn("billing_usage_logs", "customer_charge_credits", "ALTER TABLE billing_usage_logs ADD COLUMN customer_charge_credits INT NOT NULL DEFAULT 0 AFTER charged_credits");
        ensureColumn("billing_usage_logs", "margin_credits", "ALTER TABLE billing_usage_logs ADD COLUMN margin_credits INT NOT NULL DEFAULT 0 AFTER customer_charge_credits");
        ensureColumn("billing_usage_logs", "markup_ratio", "ALTER TABLE billing_usage_logs ADD COLUMN markup_ratio DECIMAL(10,4) NOT NULL DEFAULT 0 AFTER margin_credits");
        ensureColumn("billing_usage_logs", "outcome", "ALTER TABLE billing_usage_logs ADD COLUMN outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCESS'");
        ensureColumn("billing_usage_logs", "error_code", "ALTER TABLE billing_usage_logs ADD COLUMN error_code VARCHAR(64) NULL");
        ensureColumn("billing_usage_logs", "failure_stage", "ALTER TABLE billing_usage_logs ADD COLUMN failure_stage VARCHAR(64) NULL");
        ensureColumn("billing_usage_logs", "provider_error_code", "ALTER TABLE billing_usage_logs ADD COLUMN provider_error_code VARCHAR(128) NULL");
        ensureColumn("billing_usage_logs", "provider_request_id", "ALTER TABLE billing_usage_logs ADD COLUMN provider_request_id VARCHAR(128) NULL");
        ensureColumn("billing_usage_logs", "provider_charged", "ALTER TABLE billing_usage_logs ADD COLUMN provider_charged TINYINT NOT NULL DEFAULT 0");
        ensureIndex("billing_usage_logs", "idx_billing_usage_outcome_provider", "CREATE INDEX idx_billing_usage_outcome_provider ON billing_usage_logs(outcome, provider, created_at)");
        ensureTable("vendor_balance_adjustments", """
                CREATE TABLE vendor_balance_adjustments (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  billing_usage_log_id BIGINT NOT NULL,
                  vendor_account_id BIGINT NOT NULL,
                  balance_before DECIMAL(18,6) NOT NULL,
                  balance_after DECIMAL(18,6) NOT NULL,
                  deducted_amount DECIMAL(18,6) NOT NULL,
                  balance_currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_vendor_balance_adjustment_log (billing_usage_log_id),
                  KEY idx_vendor_balance_adjustment_account (vendor_account_id, created_at)
                )
                """);
        ensureColumn("agent_files", "attached_run_id", "ALTER TABLE agent_files ADD COLUMN attached_run_id BIGINT NULL");
        ensureIndex("agent_files", "idx_agent_files_attached_run", "CREATE INDEX idx_agent_files_attached_run ON agent_files(session_id, attached_run_id, id)");
        ensureColumn("agent_messages", "status", "ALTER TABLE agent_messages ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("agent_messages", "superseded_at", "ALTER TABLE agent_messages ADD COLUMN superseded_at DATETIME NULL");
        ensureColumn("agent_messages", "edited_at", "ALTER TABLE agent_messages ADD COLUMN edited_at DATETIME NULL");
        ensureColumn("agent_messages", "parent_message_id", "ALTER TABLE agent_messages ADD COLUMN parent_message_id BIGINT NULL");
        ensureIndex("agent_messages", "idx_agent_messages_session_active", "CREATE INDEX idx_agent_messages_session_active ON agent_messages(session_id, status, id)");
        ensureIndex("agent_messages", "idx_agent_messages_parent_branch", "CREATE INDEX idx_agent_messages_parent_branch ON agent_messages(session_id, parent_message_id, role, id)");
        ensureColumn("agent_sessions", "conversation_summary", "ALTER TABLE agent_sessions ADD COLUMN conversation_summary TEXT NULL COMMENT 'rolling conversation summary for compacted agent history' AFTER title");
        ensureColumn("agent_sessions", "active_leaf_message_id", "ALTER TABLE agent_sessions ADD COLUMN active_leaf_message_id BIGINT NULL");
        ensureIndex("agent_sessions", "idx_agent_sessions_active_leaf", "CREATE INDEX idx_agent_sessions_active_leaf ON agent_sessions(active_leaf_message_id)");
        ensureColumn("agent_runs", "model_config_id", "ALTER TABLE agent_runs ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("agent_runs", "parent_run_id", "ALTER TABLE agent_runs ADD COLUMN parent_run_id BIGINT NULL");
        ensureColumn("agent_runs", "source_user_message_id", "ALTER TABLE agent_runs ADD COLUMN source_user_message_id BIGINT NULL");
        ensureColumn("agent_runs", "context_snapshot_id", "ALTER TABLE agent_runs ADD COLUMN context_snapshot_id BIGINT NULL");
        ensureColumn("agent_runs", "client_request_id", "ALTER TABLE agent_runs ADD COLUMN client_request_id VARCHAR(64) NULL");
        ensureColumn("agent_runs", "preferred_tool_code", "ALTER TABLE agent_runs ADD COLUMN preferred_tool_code VARCHAR(64) NULL");
        ensureColumn("agent_runs", "user_message", "ALTER TABLE agent_runs ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("agent_runs", "developer_message", "ALTER TABLE agent_runs ADD COLUMN developer_message TEXT NULL");
        ensureColumn("agent_runs", "failure_trace_id", "ALTER TABLE agent_runs ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("agent_runs", "uk_agent_runs_user_client", "CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs(user_id, client_request_id)");
        ensureIndex("agent_runs", "idx_agent_runs_session_user_id", "CREATE INDEX idx_agent_runs_session_user_id ON agent_runs(session_id, user_id, id)");
        ensureIndex("agent_runs", "idx_agent_runs_model_config", "CREATE INDEX idx_agent_runs_model_config ON agent_runs(model_config_id)");
        ensureIndex("agent_runs", "idx_agent_runs_context_snapshot", "CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs(context_snapshot_id)");
        ensureIndex("agent_runs", "idx_agent_runs_failure_trace", "CREATE INDEX idx_agent_runs_failure_trace ON agent_runs(failure_trace_id)");
        ensureColumn("agent_tool_calls", "task_id", "ALTER TABLE agent_tool_calls ADD COLUMN task_id BIGINT NULL");
        ensureColumn("agent_tool_calls", "user_message", "ALTER TABLE agent_tool_calls ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("agent_tool_calls", "developer_message", "ALTER TABLE agent_tool_calls ADD COLUMN developer_message TEXT NULL");
        ensureColumn("agent_tool_calls", "failure_trace_id", "ALTER TABLE agent_tool_calls ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("agent_tool_calls", "idx_agent_tool_calls_task_id", "CREATE INDEX idx_agent_tool_calls_task_id ON agent_tool_calls(task_id)");
        ensureIndex("agent_tool_calls", "idx_agent_tool_calls_context_recent", "CREATE INDEX idx_agent_tool_calls_context_recent ON agent_tool_calls(user_id, status, id)");
        ensureIndex("agent_tool_calls", "idx_agent_tool_calls_failure_trace", "CREATE INDEX idx_agent_tool_calls_failure_trace ON agent_tool_calls(failure_trace_id)");
        ensureColumn("workflow_runs", "error_code", "ALTER TABLE workflow_runs ADD COLUMN error_code VARCHAR(64) NULL");
        ensureColumn("workflow_runs", "user_message", "ALTER TABLE workflow_runs ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("workflow_runs", "developer_message", "ALTER TABLE workflow_runs ADD COLUMN developer_message TEXT NULL");
        ensureColumn("workflow_runs", "failure_trace_id", "ALTER TABLE workflow_runs ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("workflow_runs", "idx_workflow_runs_failure_trace", "CREATE INDEX idx_workflow_runs_failure_trace ON workflow_runs(failure_trace_id)");
        ensureColumn("workflow_run_steps", "error_code", "ALTER TABLE workflow_run_steps ADD COLUMN error_code VARCHAR(64) NULL");
        ensureColumn("workflow_run_steps", "user_message", "ALTER TABLE workflow_run_steps ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("workflow_run_steps", "developer_message", "ALTER TABLE workflow_run_steps ADD COLUMN developer_message TEXT NULL");
        ensureColumn("workflow_run_steps", "failure_trace_id", "ALTER TABLE workflow_run_steps ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("workflow_run_steps", "idx_workflow_run_steps_failure_trace", "CREATE INDEX idx_workflow_run_steps_failure_trace ON workflow_run_steps(failure_trace_id)");
        ensureColumn("workflow_step_attempts", "user_message", "ALTER TABLE workflow_step_attempts ADD COLUMN user_message VARCHAR(255) NULL");
        ensureColumn("workflow_step_attempts", "developer_message", "ALTER TABLE workflow_step_attempts ADD COLUMN developer_message TEXT NULL");
        ensureColumn("workflow_step_attempts", "failure_trace_id", "ALTER TABLE workflow_step_attempts ADD COLUMN failure_trace_id VARCHAR(64) NULL");
        ensureIndex("workflow_step_attempts", "idx_workflow_step_attempts_failure_trace", "CREATE INDEX idx_workflow_step_attempts_failure_trace ON workflow_step_attempts(failure_trace_id)");
        ensureColumn("agent_workspace_memory_items", "source_message_id", "ALTER TABLE agent_workspace_memory_items ADD COLUMN source_message_id BIGINT NULL");
        ensureColumn("agent_workspace_memory_items", "source_tool_call_id", "ALTER TABLE agent_workspace_memory_items ADD COLUMN source_tool_call_id BIGINT NULL");
        ensureColumn("agent_workspace_memory_items", "importance", "ALTER TABLE agent_workspace_memory_items ADD COLUMN importance INT NOT NULL DEFAULT 5");
        ensureColumn("agent_workspace_memory_items", "confidence", "ALTER TABLE agent_workspace_memory_items ADD COLUMN confidence DOUBLE NOT NULL DEFAULT 0.7");
        ensureColumn("agent_workspace_memory_items", "pinned", "ALTER TABLE agent_workspace_memory_items ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0");
        ensureColumn("agent_workspace_memory_items", "tags_json", "ALTER TABLE agent_workspace_memory_items ADD COLUMN tags_json JSON NULL");
        ensureColumn("agent_workspace_memory_items", "metadata_json", "ALTER TABLE agent_workspace_memory_items ADD COLUMN metadata_json JSON NULL");
        ensureColumn("agent_workspace_memory_items", "last_accessed_at", "ALTER TABLE agent_workspace_memory_items ADD COLUMN last_accessed_at DATETIME NULL");
        ensureColumn("agent_workspace_memory_items", "access_count", "ALTER TABLE agent_workspace_memory_items ADD COLUMN access_count INT NOT NULL DEFAULT 0");
        ensureColumn("agent_workspace_memory_items", "expires_at", "ALTER TABLE agent_workspace_memory_items ADD COLUMN expires_at DATETIME NULL");
        ensureIndex("agent_workspace_memory_items", "idx_agent_memory_context_pack", "CREATE INDEX idx_agent_memory_context_pack ON agent_workspace_memory_items(workspace_id, status, pinned, importance, updated_at)");
        ensureIndex("agent_workspace_memory_items", "idx_agent_memory_user_type", "CREATE INDEX idx_agent_memory_user_type ON agent_workspace_memory_items(workspace_id, user_id, memory_type, status)");
        ensureFulltextMemoryIndex();
        ensureTable("agent_tool_descriptor_extension", """
                CREATE TABLE agent_tool_descriptor_extension (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  tool_id BIGINT NOT NULL,
                  tool_code VARCHAR(64) NOT NULL,
                  agent_enabled TINYINT NOT NULL DEFAULT 1,
                  agent_recommendable TINYINT NOT NULL DEFAULT 1,
                  agent_auto_callable TINYINT NOT NULL DEFAULT 0,
                  confirmation_policy VARCHAR(32) DEFAULT 'auto',
                  risk_level VARCHAR(16) DEFAULT 'low',
                  keywords_json TEXT DEFAULT NULL,
                  example_prompts_json TEXT DEFAULT NULL,
                  applicable_scenarios_json TEXT DEFAULT NULL,
                  not_applicable_scenarios_json TEXT DEFAULT NULL,
                  result_schema_json TEXT DEFAULT NULL,
                  output_type VARCHAR(32) DEFAULT 'text',
                  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  health_message VARCHAR(512) NULL,
                  health_checked_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_tool_code (tool_code),
                  KEY idx_enabled_recommendable (agent_enabled, agent_recommendable),
                  KEY idx_agent_tool_health (agent_enabled, health_status)
                )
                """);
        ensureColumn("agent_tool_descriptor_extension", "health_status", "ALTER TABLE agent_tool_descriptor_extension ADD COLUMN health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'");
        ensureColumn("agent_tool_descriptor_extension", "health_message", "ALTER TABLE agent_tool_descriptor_extension ADD COLUMN health_message VARCHAR(512) NULL");
        ensureColumn("agent_tool_descriptor_extension", "health_checked_at", "ALTER TABLE agent_tool_descriptor_extension ADD COLUMN health_checked_at DATETIME NULL");
        ensureIndex("agent_tool_descriptor_extension", "idx_agent_tool_health", "CREATE INDEX idx_agent_tool_health ON agent_tool_descriptor_extension(agent_enabled, health_status)");
        ensureTable("agent_skill_bundles", """
                CREATE TABLE agent_skill_bundles (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  skill_code VARCHAR(64) NOT NULL,
                  display_name VARCHAR(128) NOT NULL,
                  description VARCHAR(512) NOT NULL,
                  tool_codes_json TEXT NOT NULL,
                  sop_rules MEDIUMTEXT NULL,
                  when_to_use TEXT NULL,
                  when_not_to_use TEXT NULL,
                  field_policy_json TEXT NULL,
                  examples_json TEXT NULL,
                  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                  version INT NOT NULL DEFAULT 1,
                  published_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_agent_skill_version (skill_code, version),
                  KEY idx_agent_skill_status (status, skill_code)
                )
                """);
        seedImageGenerationSkillBundle();
        seedMusicGenerationSkillBundle();
        executeSqlIgnore("ALTER TABLE agent_run_events MODIFY COLUMN event_text MEDIUMTEXT NULL");
        ensureTable("agent_context_snapshots", """
                CREATE TABLE agent_context_snapshots (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  run_id BIGINT NOT NULL,
                  session_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  workspace_id BIGINT NULL,
                  model_config_id BIGINT NULL,
                  model_provider_code VARCHAR(64) NULL,
                  model_name VARCHAR(128) NULL,
                  strategy VARCHAR(64) NOT NULL,
                  max_history_messages INT NOT NULL DEFAULT 20,
                  history_message_count INT NOT NULL DEFAULT 0,
                  file_count INT NOT NULL DEFAULT 0,
                  file_chunk_count INT NOT NULL DEFAULT 0,
                  memory_item_count INT NOT NULL DEFAULT 0,
                  estimated_input_tokens INT NOT NULL DEFAULT 0,
                  snapshot_json JSON NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureIndex("agent_context_snapshots", "idx_agent_context_snapshots_run", "CREATE INDEX idx_agent_context_snapshots_run ON agent_context_snapshots(run_id, id)");
        ensureIndex("agent_context_snapshots", "idx_agent_context_snapshots_session", "CREATE INDEX idx_agent_context_snapshots_session ON agent_context_snapshots(session_id, id)");
        ensureIndex("agent_context_snapshots", "idx_agent_context_snapshots_user", "CREATE INDEX idx_agent_context_snapshots_user ON agent_context_snapshots(user_id, id)");
        ensureColumn("agent_context_snapshots", "payload_sha256", "ALTER TABLE agent_context_snapshots ADD COLUMN payload_sha256 CHAR(64) NULL AFTER snapshot_json");
        ensureTable("agent_model_request_snapshots", """
                CREATE TABLE agent_model_request_snapshots (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                  request_sequence INT NOT NULL, request_stage VARCHAR(64) NOT NULL, iteration_no INT NULL,
                  model_provider_code VARCHAR(64) NULL, model_name VARCHAR(128) NULL,
                  message_count INT NOT NULL DEFAULT 0, tool_count INT NOT NULL DEFAULT 0,
                  estimated_input_tokens INT NOT NULL DEFAULT 0, skill_codes_json JSON NULL,
                  payload_json JSON NULL, payload_sha256 CHAR(64) NOT NULL,
                  payload_expires_at DATETIME NOT NULL, payload_expired_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_agent_model_request_run_sequence (run_id, request_sequence),
                  KEY idx_agent_model_request_run (run_id, id),
                  KEY idx_agent_model_request_expiry (payload_expires_at, payload_expired_at)
                )
                """);
        ensureTable("agent_run_audit_reviews", """
                CREATE TABLE agent_run_audit_reviews (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL,
                  expected_tool_code VARCHAR(128) NULL, final_category VARCHAR(64) NULL,
                  review_note TEXT NULL, reviewed_by BIGINT NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_agent_run_audit_review_run (run_id)
                )
                """);
        ensureTable("ppt_project_bindings", """
                CREATE TABLE ppt_project_bindings (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  tool_id BIGINT NOT NULL,
                  banana_project_id VARCHAR(64) NOT NULL,
                  creation_type VARCHAR(32) NOT NULL,
                  title VARCHAR(255) NULL,
                  status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_banana_project (banana_project_id),
                  KEY idx_user_tool (user_id, tool_id),
                  KEY idx_user_updated (user_id, updated_at)
                )
                """);
        ensureTable("ppt_step_billing_logs", """
                CREATE TABLE ppt_step_billing_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  binding_id BIGINT NOT NULL,
                  step_code VARCHAR(64) NOT NULL,
                  credits_charged INT NOT NULL,
                  credit_log_id BIGINT NULL,
                  client_request_id VARCHAR(64) NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_binding (binding_id),
                  KEY idx_user_created (user_id, created_at),
                  UNIQUE KEY uk_binding_step_request (binding_id, step_code, client_request_id)
                )
                """);
        ensureTable("tool_workflows", """
                CREATE TABLE tool_workflows (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  tool_id BIGINT NOT NULL,
                  workflow_name VARCHAR(128) NOT NULL DEFAULT 'default',
                  nodes_json MEDIUMTEXT NOT NULL,
                  edges_json MEDIUMTEXT NOT NULL,
                  groups_json MEDIUMTEXT,
                  config_json MEDIUMTEXT,
                  version INT NOT NULL DEFAULT 1,
                  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
                  created_by BIGINT,
                  updated_by BIGINT,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_tool_workflow (tool_id, workflow_name),
                  KEY idx_workflow_tool_status (tool_id, status)
                )
                """);
        ensureTable("tool_workflow_versions", """
                CREATE TABLE tool_workflow_versions (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  workflow_id BIGINT NOT NULL,
                  version INT NOT NULL,
                  nodes_json MEDIUMTEXT NOT NULL,
                  edges_json MEDIUMTEXT NOT NULL,
                  groups_json MEDIUMTEXT,
                  config_json MEDIUMTEXT,
                  snapshot_label VARCHAR(255),
                  created_by BIGINT,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_workflow_version (workflow_id, version),
                  KEY idx_workflow_ver_created (workflow_id, created_at)
                )
                """);
        ensureTable("community_posts", """
                CREATE TABLE community_posts (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  task_id BIGINT NOT NULL,
                  modality VARCHAR(32) NOT NULL,
                  cover_url VARCHAR(1024),
                  title VARCHAR(160) NOT NULL,
                  description VARCHAR(500),
                  prompt_visible TINYINT NOT NULL DEFAULT 0,
                  prompt_snapshot MEDIUMTEXT,
                  tool_code VARCHAR(128),
                  tool_name VARCHAR(128),
                  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
                  featured TINYINT NOT NULL DEFAULT 0,
                  pinned TINYINT NOT NULL DEFAULT 0,
                  topic VARCHAR(64) NULL,
                  same_style_count BIGINT NOT NULL DEFAULT 0,
                  audit_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
                  audit_reason VARCHAR(255) NULL,
                  view_count BIGINT NOT NULL DEFAULT 0,
                  like_count BIGINT NOT NULL DEFAULT 0,
                  favorite_count BIGINT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_posts_task (task_id),
                  KEY idx_community_posts_user_status_id (user_id, status, id),
                  KEY idx_community_posts_status_id (status, id),
                  KEY idx_community_posts_discovery (status, pinned, featured, id),
                  KEY idx_community_posts_modality_id (status, modality, id),
                  KEY idx_community_posts_topic_id (status, topic, id),
                  KEY idx_community_posts_popular (status, like_count, favorite_count, id),
                  KEY idx_community_posts_same_style (status, same_style_count, id)
                )
                """);
        ensureColumn("community_posts", "featured", "ALTER TABLE community_posts ADD COLUMN featured TINYINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "pinned", "ALTER TABLE community_posts ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "topic", "ALTER TABLE community_posts ADD COLUMN topic VARCHAR(64) NULL");
        ensureColumn("community_posts", "same_style_count", "ALTER TABLE community_posts ADD COLUMN same_style_count BIGINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "audit_status", "ALTER TABLE community_posts ADD COLUMN audit_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED'");
        ensureColumn("community_posts", "audit_reason", "ALTER TABLE community_posts ADD COLUMN audit_reason VARCHAR(255) NULL");
        ensureTable("community_post_likes", """
                CREATE TABLE community_post_likes (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  post_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_post_likes_user (post_id, user_id)
                )
                """);
        ensureTable("community_post_favorites", """
                CREATE TABLE community_post_favorites (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  post_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_post_favorites_user (post_id, user_id)
                )
                """);
        ensureColumn("community_posts", "detail_click_count", "ALTER TABLE community_posts ADD COLUMN detail_click_count BIGINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "share_count", "ALTER TABLE community_posts ADD COLUMN share_count BIGINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "quality_score", "ALTER TABLE community_posts ADD COLUMN quality_score BIGINT NOT NULL DEFAULT 0");
        ensureColumn("community_posts", "last_featured_at", "ALTER TABLE community_posts ADD COLUMN last_featured_at DATETIME NULL");
        ensureColumn("community_posts", "media_url", "ALTER TABLE community_posts ADD COLUMN media_url VARCHAR(1024) NULL");
        ensureIndex("community_posts", "idx_community_posts_status_topic_id", "CREATE INDEX idx_community_posts_status_topic_id ON community_posts(status, topic, id)");
        ensureIndex("community_posts", "idx_community_posts_status_modality_id", "CREATE INDEX idx_community_posts_status_modality_id ON community_posts(status, modality, id)");
        ensureIndex("community_posts", "idx_community_posts_quality", "CREATE INDEX idx_community_posts_quality ON community_posts(status, audit_status, pinned, featured, quality_score, id)");
        ensureTable("community_post_tags", """
                CREATE TABLE community_post_tags (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  post_id BIGINT NOT NULL,
                  tag VARCHAR(32) NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_post_tags_post_tag (post_id, tag),
                  KEY idx_community_post_tags_tag (tag, post_id)
                )
                """);
        ensureTable("community_events", """
                CREATE TABLE community_events (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  post_id BIGINT NULL,
                  user_id BIGINT NULL,
                  event_type VARCHAR(48) NOT NULL,
                  source VARCHAR(64) NULL,
                  tool_code VARCHAR(128) NULL,
                  task_id BIGINT NULL,
                  credits INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_community_events_type_created (event_type, created_at),
                  KEY idx_community_events_post_type (post_id, event_type),
                  KEY idx_community_events_tool (tool_code, event_type)
                )
                """);
        ensureTable("community_collections", """
                CREATE TABLE community_collections (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  name VARCHAR(80) NOT NULL,
                  default_collection TINYINT NOT NULL DEFAULT 0,
                  item_count BIGINT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  KEY idx_community_collections_user (user_id, id),
                  KEY idx_community_default_collection (user_id, default_collection)
                )
                """);
        ensureTable("community_collection_items", """
                CREATE TABLE community_collection_items (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  collection_id BIGINT NOT NULL,
                  post_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_collection_item (collection_id, post_id),
                  KEY idx_community_collection_items_user (user_id, collection_id, id)
                )
                """);
        ensureTable("community_post_reports", """
                CREATE TABLE community_post_reports (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  post_id BIGINT NOT NULL,
                  reporter_user_id BIGINT NOT NULL,
                  reason VARCHAR(500) NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                  admin_note VARCHAR(500) NULL,
                  reviewed_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_community_post_reports_user (post_id, reporter_user_id),
                  KEY idx_community_post_reports_status_created (status, created_at, id),
                  KEY idx_community_post_reports_post (post_id, status, id)
                )
                """);
        ensureIndex("community_posts", "idx_community_posts_discovery", "CREATE INDEX idx_community_posts_discovery ON community_posts(status, pinned, featured, id)");
        ensureIndex("community_posts", "idx_community_posts_modality_id", "CREATE INDEX idx_community_posts_modality_id ON community_posts(status, modality, id)");
        ensureIndex("community_posts", "idx_community_posts_topic_id", "CREATE INDEX idx_community_posts_topic_id ON community_posts(status, topic, id)");
        ensureIndex("community_posts", "idx_community_posts_popular", "CREATE INDEX idx_community_posts_popular ON community_posts(status, like_count, favorite_count, id)");
        ensureIndex("community_posts", "idx_community_posts_same_style", "CREATE INDEX idx_community_posts_same_style ON community_posts(status, same_style_count, id)");
    }

    private void ensureTable(String tableName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, tableName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure database table " + tableName, exception);
        }
    }

    private void assertNoPaidMembershipOrdersBeforeBucketMigration() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "credit_accounts")
                    || !tableExists(connection, "credit_recharge_orders")
                    || !columnExists(connection, "credit_accounts", "bucket_schema_version")) {
                return;
            }
            try (ResultSet result = connection.createStatement().executeQuery("""
                    SELECT COUNT(*)
                    FROM credit_recharge_orders
                    WHERE order_type = 'MEMBERSHIP'
                      AND status IN ('PAID', 'CREDITED')
                      AND EXISTS (
                        SELECT 1 FROM credit_accounts WHERE bucket_schema_version < 2
                      )
                    """)) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new IllegalStateException(
                            "Membership balance migration aborted: paid membership orders exist");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate membership balance migration", exception);
        }
    }

    private void ensureColumn(String tableName, String columnName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!columnExists(connection, tableName, columnName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure database column " + tableName + "." + columnName, exception);
        }
    }

    private void normalizeToolAndModelCapabilities() {
        normalizeDigitalHumanTemplateCapabilities();
        executeSql("""
                UPDATE agent_model_configs
                SET provider = 'seedance',
                    capabilities = '["VIDEO_GENERATION"]',
                    execution_task = 'video_generation',
                    updated_at = CURRENT_TIMESTAMP
                WHERE is_deleted = 0
                  AND provider = 'volcengine_images'
                  AND LOWER(model_name) LIKE '%seedance%'
                """);
        normalizeStoredCapabilityColumn("agent_model_configs", "capabilities", "provider", false);
        normalizeStoredCapabilityColumn("model_provider_metadata", "capabilities_json", "provider_code", false);
        normalizeStoredCapabilityColumn("ai_tools", "required_model_capabilities", null, true);
        executeSql("""
                UPDATE ai_tools
                SET required_model_capabilities = CASE UPPER(COALESCE(
                      NULLIF(TRIM(execution_handler), ''),
                      NULLIF(TRIM(tool_type), ''),
                      'TEXT_GENERATION'
                    ))
                      WHEN 'DIGITAL_HUMAN' THEN '["VIDEO_GENERATION"]'
                      WHEN 'IMAGE_TO_IMAGE' THEN '["IMAGE_GENERATION"]'
                      WHEN 'IMAGE_UNDERSTANDING' THEN '["TEXT_GENERATION","VISION_INPUT"]'
                      WHEN 'AGENT' THEN '["TEXT_GENERATION"]'
                      WHEN 'IMAGE_GENERATION' THEN '["IMAGE_GENERATION"]'
                      WHEN 'VIDEO_GENERATION' THEN '["VIDEO_GENERATION"]'
                      WHEN 'MUSIC_GENERATION' THEN '["MUSIC_GENERATION"]'
                      WHEN 'TEXT_TO_SPEECH' THEN '["TEXT_TO_SPEECH"]'
                      WHEN 'SPEECH_TO_TEXT' THEN '["SPEECH_TO_TEXT"]'
                      ELSE '["TEXT_GENERATION"]'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE required_model_capabilities IS NULL
                   OR TRIM(required_model_capabilities) = ''
                """);
        clearIncompatibleDigitalHumanBindings();
    }

    private void normalizeDigitalHumanTemplateCapabilities() {
        List<StoredTemplateHandlerConfigRow> rows = jdbcTemplate.query("""
                SELECT id, handler_config_json
                FROM tool_templates
                WHERE UPPER(COALESCE(execution_handler, '')) = 'DIGITAL_HUMAN'
                  AND handler_config_json IS NOT NULL
                  AND TRIM(handler_config_json) <> ''
                """, (result, rowNumber) -> new StoredTemplateHandlerConfigRow(
                result.getLong("id"),
                result.getString("handler_config_json")
        ));
        for (StoredTemplateHandlerConfigRow row : rows) {
            String normalized = normalizeDigitalHumanTemplateHandlerConfig(row.handlerConfigJson());
            if (normalized.equals(row.handlerConfigJson())) {
                continue;
            }
            jdbcTemplate.update("""
                    UPDATE tool_templates
                    SET handler_config_json = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, normalized, row.id());
        }
    }

    static String normalizeDigitalHumanTemplateHandlerConfig(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }
        try {
            JsonNode decoded = OBJECT_MAPPER.readTree(rawJson);
            if (!(decoded instanceof ObjectNode objectNode)) {
                return rawJson;
            }
            JsonNode requiredCapability = objectNode.get("requiredCapability");
            if (requiredCapability == null
                    || !requiredCapability.isTextual()
                    || !"DIGITAL_HUMAN".equalsIgnoreCase(requiredCapability.asText().trim())) {
                return rawJson;
            }
            objectNode.put("requiredCapability", "VIDEO_GENERATION");
            return OBJECT_MAPPER.writeValueAsString(objectNode);
        } catch (Exception exception) {
            return rawJson;
        }
    }

    private void normalizeStoredCapabilityColumn(String tableName, String columnName,
                                                 String providerColumn,
                                                 boolean mapDigitalHumanToVideo) {
        String providerExpression = providerColumn == null ? "NULL" : providerColumn;
        String selectSql = "SELECT id, " + columnName + ", " + providerExpression
                + " AS provider_code FROM " + tableName
                + " WHERE " + columnName + " IS NOT NULL AND TRIM(" + columnName + ") <> ''";
        List<StoredCapabilityRow> rows = jdbcTemplate.query(
                selectSql,
                (result, rowNumber) -> new StoredCapabilityRow(
                        result.getLong("id"),
                        result.getString(columnName),
                        result.getString("provider_code")
                )
        );
        for (StoredCapabilityRow row : rows) {
            List<String> normalized = providerColumn == null
                    ? normalizeLegacyCapabilities(row.capabilitiesJson(), mapDigitalHumanToVideo)
                    : normalizeLegacyModelCapabilities(row.capabilitiesJson(), row.provider());
            if (providerColumn == null && requiresToolCapabilityBackfill(normalized)) {
                jdbcTemplate.update(
                        "UPDATE " + tableName + " SET " + columnName
                                + " = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        row.id()
                );
                continue;
            }
            if (normalized == null) {
                continue;
            }
            String normalizedJson = toJson(normalized);
            if (normalizedJson.equals(row.capabilitiesJson())) {
                continue;
            }
            jdbcTemplate.update(
                    "UPDATE " + tableName + " SET " + columnName
                            + " = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    normalizedJson,
                    row.id()
            );
        }
    }

    private void clearIncompatibleDigitalHumanBindings() {
        List<Long> invalidToolIds = jdbcTemplate.query("""
                SELECT tool.id,
                       model.provider AS model_provider,
                       model.capabilities AS model_capabilities,
                       metadata.capabilities_json AS provider_capabilities
                FROM ai_tools tool
                LEFT JOIN agent_model_configs model
                  ON model.id = tool.model_config_id AND model.is_deleted = 0
                LEFT JOIN model_provider_metadata metadata
                  ON metadata.provider_code = model.provider AND metadata.enabled = 1
                WHERE UPPER(COALESCE(tool.execution_handler, '')) = 'DIGITAL_HUMAN'
                  AND tool.model_config_id IS NOT NULL
                """, (result, rowNumber) -> {
            boolean supportedProvider = isDigitalHumanVideoProvider(result.getString("model_provider"));
            List<String> capabilities = normalizeLegacyCapabilities(
                    result.getString("model_capabilities"), false);
            if (capabilities == null || capabilities.isEmpty()) {
                capabilities = normalizeLegacyCapabilities(
                        result.getString("provider_capabilities"), false);
            }
            return supportedProvider && capabilities != null && capabilities.contains("VIDEO_GENERATION")
                    ? null
                    : result.getLong("id");
        });
        invalidToolIds.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(toolId -> jdbcTemplate.update("""
                        UPDATE ai_tools
                        SET model_config_id = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                """, toolId));
    }

    static boolean isDigitalHumanVideoProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return "seedance".equals(normalized) || "infinitetalk".equals(normalized);
    }

    static List<String> normalizeLegacyCapabilities(String rawJson, boolean mapDigitalHumanToVideo) {
        return normalizeLegacyCapabilities(rawJson, mapDigitalHumanToVideo ? "VIDEO_GENERATION" : null);
    }

    static List<String> normalizeLegacyModelCapabilities(String rawJson, String provider) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String replacement = switch (normalizedProvider) {
            case "seedance", "infinitetalk" -> "VIDEO_GENERATION";
            case "siliconflow", "siliconflow_images" -> "IMAGE_GENERATION";
            default -> null;
        };
        return normalizeLegacyCapabilities(rawJson, replacement);
    }

    static boolean requiresToolCapabilityBackfill(List<String> normalizedCapabilities) {
        return normalizedCapabilities == null || normalizedCapabilities.isEmpty();
    }

    private static List<String> normalizeLegacyCapabilities(String rawJson,
                                                            String digitalHumanReplacement) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            Object decoded = OBJECT_MAPPER.readValue(rawJson, Object.class);
            if (!(decoded instanceof List<?> values)) {
                return null;
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                String capability = value.toString().trim().toUpperCase(Locale.ROOT);
                if (capability.isBlank()) {
                    continue;
                }
                if ("DIGITAL_HUMAN".equals(capability)) {
                    if (digitalHumanReplacement != null) {
                        normalized.add(digitalHumanReplacement);
                    }
                    continue;
                }
                normalized.add(capability);
            }
            return List.copyOf(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record StoredCapabilityRow(long id, String capabilitiesJson, String provider) {
    }

    private record StoredTemplateHandlerConfigRow(long id, String handlerConfigJson) {
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!indexExists(connection, tableName, indexName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure database index " + tableName + "." + indexName, exception);
        }
    }

    private void ensureFulltextMemoryIndex() {
        try (Connection connection = dataSource.getConnection()) {
            if (!indexExists(connection, "agent_workspace_memory_items", "ft_memory_search")) {
                connection.createStatement().execute("ALTER TABLE agent_workspace_memory_items ADD FULLTEXT INDEX ft_memory_search (title, content) WITH PARSER ngram");
            }
        } catch (SQLException ignored) {
            // H2 and some MySQL variants may not support ngram fulltext in local tests; runtime retrieval has fallback search.
        }
    }

    private void executeSql(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to execute schema compatibility SQL", exception);
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String[] tableCandidates = {tableName, tableName.toUpperCase()};
        String[] columnCandidates = {columnName, columnName.toUpperCase()};
        for (String table : tableCandidates) {
            for (String column : columnCandidates) {
                try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void executeSqlIgnore(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate(sql);
        } catch (SQLException ignored) {
            // Compatibility DDL is best-effort across MySQL and H2 test schemas.
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        String[] tableCandidates = {tableName, tableName.toUpperCase()};
        String[] indexCandidates = {indexName, indexName.toUpperCase()};
        for (String table : tableCandidates) {
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
                while (indexes.next()) {
                    String existingIndex = indexes.getString("INDEX_NAME");
                    if (existingIndex == null) {
                        continue;
                    }
                    for (String index : indexCandidates) {
                        if (existingIndex.equals(index)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        String[] tableCandidates = {tableName, tableName.toUpperCase()};
        for (String table : tableCandidates) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void seedGptImageApiKeysFromEnv() {
        seedGptImageApiKeyForHost("shiyunapi.com", appProperties.getAgent().getGptImageShiyunApiKey());
        seedGptImageApiKeyForHost("ofox.ai", appProperties.getAgent().getGptImageOfoxApiKey());
    }

    private void normalizeGptImageToolFieldOptions() {
        String sizeOptionsJson = """
                [{"label":"智能","value":"auto"},{"label":"2:3","value":"1024x1536"},{"label":"1:1","value":"1024x1024"},{"label":"3:2","value":"1536x1024"}]
                """.trim();
        jdbcTemplate.update("""
                UPDATE tool_field_schema_items
                SET field_type = 'aspect_ratio',
                    options_json = ?,
                    updated_at = NOW()
                WHERE status = 'ACTIVE'
                  AND field_key IN ('aspectRatio', 'aspect_ratio', 'imageRatio', 'image_size', 'imageSize', 'size')
                  AND (
                    field_type <> 'aspect_ratio'
                    OR options_json IS NULL
                    OR options_json = ''
                    OR REPLACE(options_json, ' ', '') LIKE '%"value":"16:9"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"value":"16：9"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"value":"9:16"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"value":"9：16"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"9:16","value":"1024x1536"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"9：16","value":"1024x1536"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"3:4","value":"1024x1536"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"4:3","value":"1536x1024"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"16:9","value":"1536x1024"%'
                    OR REPLACE(options_json, ' ', '') LIKE '%"label":"16：9","value":"1536x1024"%'
                  )
                  AND schema_id IN (
                    SELECT s.id
                    FROM tool_field_schemas s
                    JOIN ai_tools t ON t.id = s.tool_id
                    LEFT JOIN agent_model_configs m ON m.id = t.model_config_id
                    WHERE s.status = 'ACTIVE'
                      AND t.is_deleted = 0
                      AND (
                        LOWER(COALESCE(t.tool_code, '')) LIKE '%gpt%image%'
                        OR LOWER(COALESCE(t.tool_name, '')) LIKE '%gpt%image%'
                        OR LOWER(COALESCE(t.tool_name, '')) LIKE '%image2%'
                        OR LOWER(COALESCE(m.model_name, '')) LIKE '%gpt-image%'
                        OR LOWER(COALESCE(m.provider, '')) IN ('ofox_openai_images', 'openai_images_gateway')
                      )
                  )
                """, sizeOptionsJson);
    }

    private void seedGptImageApiKeyForHost(String hostFragment, String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.trim().startsWith("replace-with-")) {
            return;
        }
        String normalizedKey = apiKey.trim();
        String hostPattern = "%" + hostFragment + "%";
        jdbcTemplate.update("""
                UPDATE model_vendor_accounts
                SET api_key = ?, updated_at = NOW()
                WHERE is_deleted = 0
                  AND enabled = 1
                  AND base_url LIKE ?
                  AND (api_key IS NULL OR api_key = '')
                """, normalizedKey, hostPattern);
        jdbcTemplate.update("""
                UPDATE agent_model_configs
                SET api_key = ?, updated_at = NOW()
                WHERE is_deleted = 0
                  AND (base_url LIKE ? OR vendor_account_id IN (
                    SELECT id FROM model_vendor_accounts
                    WHERE is_deleted = 0 AND base_url LIKE ?
                  ))
                  AND (api_key IS NULL OR api_key = '')
                """, normalizedKey, hostPattern, hostPattern);
    }

    private void createUserIfAbsent(String username, String password, String nickname, UserType userType) {
        if (userMapper.findByUsername(username).isPresent()) {
            return;
        }
        User user = new User();
        publicUserIdentityService.assignPublicCode(user);
        referralCodeService.assignReferralCode(user);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setUserType(userType.name());
        user.setStatus(UserStatus.ACTIVE.name());
        userMapper.insertAndReturnId(user);
    }
}

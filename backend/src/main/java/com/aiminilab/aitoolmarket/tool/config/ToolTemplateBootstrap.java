package com.aiminilab.aitoolmarket.tool.config;

import com.aiminilab.aitoolmarket.common.enums.ExecutionHandler;
import com.aiminilab.aitoolmarket.common.enums.ToolModality;
import com.aiminilab.aitoolmarket.common.enums.ToolType;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;
import com.aiminilab.aitoolmarket.tool.entity.ToolTemplateField;
import com.aiminilab.aitoolmarket.tool.mapper.ToolTemplateFieldMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolTemplateMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class ToolTemplateBootstrap {

    private final ToolTemplateMapper toolTemplateMapper;
    private final ToolTemplateFieldMapper toolTemplateFieldMapper;
    private final DataSource dataSource;

    public ToolTemplateBootstrap(ToolTemplateMapper toolTemplateMapper,
                                 ToolTemplateFieldMapper toolTemplateFieldMapper,
                                 JdbcTemplate jdbcTemplate) {
        this.toolTemplateMapper = toolTemplateMapper;
        this.toolTemplateFieldMapper = toolTemplateFieldMapper;
        this.dataSource = jdbcTemplate.getDataSource();
    }

    public void ensureSchemaAndSeed() {
        ensureTable("tool_templates", """
                CREATE TABLE tool_templates (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  template_code VARCHAR(128) NOT NULL UNIQUE,
                  template_name VARCHAR(128) NOT NULL,
                  tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
                  execution_handler VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
                  input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
                  output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
                  config_note TEXT,
                  default_system_prompt TEXT,
                  default_user_prompt_template MEDIUMTEXT,
                  default_output_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
                  handler_config_json JSON,
                  suggested_model_config_id BIGINT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  sort_order INT NOT NULL DEFAULT 0,
                  is_system TINYINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureTable("tool_template_fields", """
                CREATE TABLE tool_template_fields (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  template_id BIGINT NOT NULL,
                  field_key VARCHAR(128) NOT NULL,
                  field_name VARCHAR(128) NOT NULL,
                  field_type VARCHAR(32) NOT NULL,
                  placeholder VARCHAR(255),
                  options_json JSON,
                  validation_json JSON,
                  required TINYINT NOT NULL DEFAULT 0,
                  sort_order INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        ensureColumn("ai_tools", "template_id", "ALTER TABLE ai_tools ADD COLUMN template_id BIGINT NULL");
        ensureColumn("ai_tools", "execution_handler", "ALTER TABLE ai_tools ADD COLUMN execution_handler VARCHAR(32) NULL");
        backfillExecutionHandlers();
        if (toolTemplateMapper.selectCount(null) == 0) {
            seedSystemTemplates();
        }
        ensureTextToSpeechTemplate();
        ensurePolloMediaTemplates();
        ensureMusicGenerationTemplate();
    }

    private void backfillExecutionHandlers() {
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'DIGITAL_HUMAN'
                WHERE tool_code = 'digital_human_agent'
                  AND (execution_handler IS NULL OR execution_handler = '')
                """);
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'IMAGE_GENERATION'
                WHERE tool_type = 'IMAGE_GENERATION'
                  AND (execution_handler IS NULL OR execution_handler = '')
                """);
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'VIDEO_GENERATION'
                WHERE tool_type = 'VIDEO_GENERATION'
                  AND (execution_handler IS NULL OR execution_handler = '')
                """);
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'TEXT_TO_SPEECH'
                WHERE tool_type = 'TEXT_TO_SPEECH'
                  AND (execution_handler IS NULL OR execution_handler = '')
                """);
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'MUSIC_GENERATION'
                WHERE tool_type = 'MUSIC_GENERATION'
                  AND (execution_handler IS NULL OR execution_handler = '')
                """);
        executeSql("""
                UPDATE ai_tools
                SET execution_handler = 'TEXT_GENERATION'
                WHERE (execution_handler IS NULL OR execution_handler = '')
                """);
    }

    private void seedSystemTemplates() {
        insertTemplate(
                "text_generation_default",
                "通用文案生成",
                ToolType.TEXT_GENERATION,
                ExecutionHandler.TEXT_GENERATION,
                ToolModality.TEXT,
                ToolModality.TEXT,
                "适用于主题、语气、字数等通用文本生成表单。",
                "你是一名专业的中文内容创作助手。",
                "主题：{{topic}}\n语气风格：{{tone}}\n字数：{{length}}\n补充要求：{{requirements}}",
                1,
                List.of(
                        field("topic", "主题", "textarea", "说明要生成的内容主题、产品或场景", null, true, 1),
                        field("tone", "语气风格", "select", null,
                                options("专业", "亲切", "种草", "高级", "幽默"), true, 2),
                        field("length", "字数", "number", "例如 200", null, false, 3),
                        field("requirements", "补充要求", "textarea", "禁用词、必须包含的信息、目标人群等", null, false, 4)
                )
        );
        insertTemplate(
                "image_generation_default",
                "文生图",
                ToolType.IMAGE_GENERATION,
                ExecutionHandler.IMAGE_GENERATION,
                ToolModality.TEXT,
                ToolModality.IMAGE,
                "需要配置画面描述、比例、风格、生成数量与反向提示词。",
                null,
                "画面描述：{{prompt}}\n画面比例：{{aspectRatio}}\n风格：{{style}}\n生成数量：{{count}}\n反向提示词：{{negativePrompt}}",
                2,
                List.of(
                        field("prompt", "画面描述", "textarea", "描述主体、场景、光线、构图和细节", null, true, 1),
                        field("aspectRatio", "画面比例", "aspect_ratio", null,
                                imageAspectRatioOptions(), true, 2),
                        field("style", "风格", "select", null,
                                options("写实", "电商", "插画", "动漫", "极简", "国潮"), false, 3),
                        field("count", "生成数量", "slider", "1",
                                "{\"slider\":{\"min\":1,\"max\":9,\"step\":1},\"defaultValue\":1}", false, 4),
                        field("negativePrompt", "反向提示词", "textarea", "不希望出现的元素", null, false, 5)
                )
        );
        insertTemplate(
                "video_generation_default",
                "视频生成",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.TEXT,
                ToolModality.VIDEO,
                "描述镜头、比例与时长，由视频生成 Handler 处理。",
                null,
                "视频描述：{{prompt}}\n视频比例：{{aspectRatio}}\n时长秒数：{{duration}}",
                3,
                List.of(
                        field("prompt", "视频描述", "textarea", "描述镜头、主体、动作、风格和时长", null, true, 1),
                        field("aspectRatio", "视频比例", "aspect_ratio", null,
                                videoAspectRatioOptions(), true, 2),
                        field("duration", "时长秒数", "number", "例如 5", null, false, 3)
                )
        );
        insertTemplate(
                "digital_human_default",
                "数字人视频",
                ToolType.AGENT,
                ExecutionHandler.DIGITAL_HUMAN,
                ToolModality.MULTIMODAL,
                ToolModality.VIDEO,
                "数字人口播视频：主题、脚本、形象、场景与成片参数。",
                "你是一个数字人视频生成智能体，负责把用户输入转成适合视频生成模型的清晰画面描述。",
                """
                        视频主题：{{videoTopic}}
                        口播脚本：{{script}}
                        数字人形象：{{avatarStyle}}
                        视频场景：{{scene}}
                        画面比例：{{aspectRatio}}
                        视频时长要求：{{duration}}
                        品牌/产品：{{brandName}}
                        画面要求：{{visualRequirements}}
                        负面提示词：{{negativePrompt}}""",
                4,
                List.of(
                        field("videoTopic", "视频主题", "textarea", "例如：新品发布口播", null, true, 1),
                        field("script", "口播脚本", "textarea", "填写完整口播文案", null, true, 2),
                        field("avatarStyle", "数字人形象", "select", "选择数字人视觉风格",
                                options("职业主播", "科技感主持人", "亲和力导购", "知识博主"), true, 3),
                        field("scene", "视频场景", "select", "选择数字人所在场景",
                                options("直播间", "产品展示台", "办公室", "纯色演播室"), true, 4),
                        field("aspectRatio", "画面比例", "aspect_ratio", "选择视频比例",
                                videoAspectRatioOptions(), true, 5),
                        field("duration", "视频时长要求", "select",
                                "选择期望成片时长",
                                options("5 秒", "10 秒", "15 秒", "30 秒", "60 秒"), true, 6),
                        field("brandName", "品牌/产品", "text", "例如：澄光实验室补水精华", null, false, 7),
                        field("visualRequirements", "画面要求", "textarea", "例如：明亮干净、人物半身出镜", null, false, 8),
                        field("negativePrompt", "负面提示词", "textarea", "例如：画面变形、字幕错乱", null, false, 9)
                )
        );
        insertTextToSpeechTemplate();
        insertMusicGenerationTemplate();
    }

    private void ensureTextToSpeechTemplate() {
        if (toolTemplateMapper.findByCode("text_to_speech_default").isPresent()) {
            return;
        }
        insertTextToSpeechTemplate();
    }

    private void ensurePolloMediaTemplates() {
        ensurePolloTemplate(
                "image_outpainting",
                "AI 图像扩展器",
                ToolType.IMAGE_TO_IMAGE,
                ExecutionHandler.IMAGE_GENERATION,
                ToolModality.IMAGE,
                ToolModality.IMAGE,
                uiConfigNote("AI 图像扩展器", "上传图片并向外扩展画面，补全天空、背景、建筑、风景或商品留白。", "comparison"),
                null,
                """
                        Original image: {{sourceImageUrl}}
                        Task: extend the image canvas outward.
                        Direction: {{expansionDirection}}
                        Target aspect ratio: {{aspectRatio}}
                        Strength: {{strength}}
                        User request: {{prompt}}
                        Preserve the original subject, lighting, perspective, and style.
                        """,
                handlerConfig("image", "image_outpainting", "comparison", "IMAGE_GENERATION"),
                20,
                List.of(
                        field("sourceImageUrl", "上传图片", "image", "上传需要扩展画面的原图", null, true, 1),
                        field("prompt", "扩图提示词", "textarea", "描述希望 AI 如何补全画面", null, false, 2),
                        field("expansionDirection", "扩展方向", "radio", null, options("四周扩展", "向左扩展", "向右扩展", "向上扩展", "向下扩展"), false, 3),
                        field("aspectRatio", "目标比例", "radio", null, options("16:9", "9:16", "1:1", "4:3"), false, 4),
                        field("strength", "扩展强度", "slider", "0-100，数值越高新增区域越明显", null, false, 5),
                        field("outputFormat", "输出格式", "radio", null, options("png", "jpg", "webp"), false, 6)
                )
        );
        ensurePolloTemplate(
                "image_background_removal",
                "图片背景去除器",
                ToolType.IMAGE_TO_IMAGE,
                ExecutionHandler.IMAGE_GENERATION,
                ToolModality.IMAGE,
                ToolModality.IMAGE,
                uiConfigNote("图片背景去除器", "上传商品、人像或物体图片，一键去除背景并输出干净透明图。", "comparison"),
                null,
                """
                        Original image: {{sourceImageUrl}}
                        Remove the background cleanly.
                        Edge refinement: {{edgeRefine}}
                        Background mode: {{backgroundMode}}
                        Output format: {{outputFormat}}
                        Keep the main subject unchanged with clean edges.
                        """,
                handlerConfig("image", "image_background_removal", "comparison", "IMAGE_GENERATION"),
                21,
                List.of(
                        field("sourceImageUrl", "上传图片", "image", "上传需要去除背景的图片", null, true, 1),
                        field("backgroundMode", "背景类型", "radio", null, options("透明背景", "白色背景", "纯色背景"), false, 2),
                        field("edgeRefine", "边缘精修", "slider", "0-100，数值越高边缘处理越强", null, false, 3),
                        field("outputFormat", "输出格式", "radio", null, options("png", "jpg", "webp"), false, 4)
                )
        );
        ensurePolloTemplate(
                "image_style_transfer",
                "图片风格变换",
                ToolType.IMAGE_TO_IMAGE,
                ExecutionHandler.IMAGE_GENERATION,
                ToolModality.IMAGE,
                ToolModality.IMAGE,
                uiConfigNote("图片风格变换", "上传原图并选择风格，让 AI 保留主体结构并生成不同视觉效果。", "comparison"),
                null,
                """
                        Original image: {{sourceImageUrl}}
                        Style: {{style}}
                        Strength: {{strength}}
                        Extra request: {{prompt}}
                        Preserve key subject identity and composition while applying the selected style.
                        """,
                handlerConfig("image", "image_style_transfer", "comparison", "IMAGE_GENERATION"),
                22,
                List.of(
                        field("sourceImageUrl", "上传图片", "image", "上传需要风格化的原图", null, true, 1),
                        field("style", "目标风格", "radio", null, options("写实摄影", "动漫", "插画", "电商海报", "国潮", "极简"), false, 2),
                        field("prompt", "补充提示词", "textarea", "补充风格细节、颜色、场景或禁用要求", null, false, 3),
                        field("strength", "风格强度", "slider", "0-100，数值越高风格变化越明显", null, false, 4),
                        field("outputFormat", "输出格式", "radio", null, options("png", "jpg", "webp"), false, 5)
                )
        );
        ensurePolloTemplate(
                "image_face_swap",
                "照片换脸",
                ToolType.IMAGE_TO_IMAGE,
                ExecutionHandler.IMAGE_GENERATION,
                ToolModality.MULTIMODAL,
                ToolModality.IMAGE,
                uiConfigNote("照片换脸", "上传目标图片和参考人脸，生成自然的 AI 换脸效果。", "comparison"),
                null,
                """
                        Target image: {{sourceImageUrl}}
                        Reference face: {{referenceImageUrl}}
                        Strength: {{strength}}
                        Preserve target pose, lighting, and expression while transferring identity from the reference face.
                        """,
                handlerConfig("image", "image_face_swap", "comparison", "IMAGE_GENERATION"),
                23,
                List.of(
                        field("sourceImageUrl", "目标图片", "image", "上传需要换脸的目标图片", null, true, 1),
                        field("referenceImageUrl", "参考人脸", "image", "上传要替换进去的人脸参考图", null, true, 2),
                        field("strength", "融合强度", "slider", "0-100，数值越高人脸身份越明显", null, false, 3),
                        field("outputFormat", "输出格式", "radio", null, options("png", "jpg", "webp"), false, 4)
                )
        );
        ensurePolloTemplate(
                "video_face_swap",
                "视频换脸",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.MULTIMODAL,
                ToolModality.VIDEO,
                uiConfigNote("视频换脸", "上传视频和参考人脸，生成自然稳定的视频换脸效果。", "effect"),
                null,
                "Video: {{sourceVideoUrl}}\nReference face: {{referenceImageUrl}}\nPreserve motion, lighting, and expression.",
                handlerConfig("video", "video_face_swap", "effect", "VIDEO_GENERATION"),
                30,
                List.of(
                        field("sourceVideoUrl", "上传视频", "file", "上传需要换脸的视频", null, true, 1),
                        field("referenceImageUrl", "参考人脸", "image", "上传参考人脸图片", null, true, 2),
                        field("duration", "处理时长", "radio", null, options("5", "10", "15", "30"), false, 3),
                        field("aspectRatio", "视频比例", "radio", null, options("16:9", "9:16", "1:1"), false, 4)
                )
        );
        ensurePolloTemplate(
                "image_to_video",
                "图生视频",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.IMAGE,
                ToolModality.VIDEO,
                uiConfigNote("图生视频", "上传一张图片并描述镜头运动，让静态画面变成短视频。", "effect"),
                null,
                "Image: {{sourceImageUrl}}\nMotion prompt: {{prompt}}\nDuration: {{duration}}\nAspect ratio: {{aspectRatio}}",
                handlerConfig("video", "image_to_video", "effect", "VIDEO_GENERATION"),
                31,
                List.of(
                        field("sourceImageUrl", "上传图片", "image", "上传需要动起来的图片", null, true, 1),
                        field("prompt", "运动描述", "textarea", "描述镜头运动、主体动作和氛围", null, true, 2),
                        field("duration", "视频时长", "radio", null, options("5", "10", "15"), false, 3),
                        field("aspectRatio", "视频比例", "radio", null, options("16:9", "9:16", "1:1"), false, 4)
                )
        );
        ensurePolloTemplate(
                "video_enhancer",
                "视频画质提升",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.VIDEO,
                ToolModality.VIDEO,
                uiConfigNote("视频画质提升", "上传视频后增强清晰度、细节和色彩表现。", "effect"),
                null,
                "Video: {{sourceVideoUrl}}\nEnhance mode: {{enhanceMode}}\nStrength: {{strength}}",
                handlerConfig("video", "video_enhancer", "effect", "VIDEO_GENERATION"),
                32,
                List.of(
                        field("sourceVideoUrl", "上传视频", "file", "上传需要增强的视频", null, true, 1),
                        field("enhanceMode", "增强模式", "radio", null, options("清晰度增强", "降噪", "色彩增强", "综合增强"), false, 2),
                        field("strength", "增强强度", "slider", "0-100，数值越高增强越明显", null, false, 3)
                )
        );
        ensurePolloTemplate(
                "subtitle_remover",
                "视频字幕移除器",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.VIDEO,
                ToolModality.VIDEO,
                uiConfigNote("视频字幕移除器", "上传带字幕视频，AI 自动移除字幕并补全画面。", "effect"),
                null,
                "Video: {{sourceVideoUrl}}\nRemove subtitles and reconstruct the background naturally.",
                handlerConfig("video", "subtitle_remover", "effect", "VIDEO_GENERATION"),
                33,
                List.of(
                        field("sourceVideoUrl", "上传视频", "file", "上传需要移除字幕的视频", null, true, 1),
                        field("maskArea", "字幕区域", "radio", null, options("自动识别", "底部字幕", "顶部字幕"), false, 2),
                        field("strength", "修复强度", "slider", "0-100，数值越高修复越强", null, false, 3)
                )
        );
        ensurePolloTemplate(
                "motion_transfer",
                "动作替换",
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.VIDEO_GENERATION,
                ToolModality.MULTIMODAL,
                ToolModality.VIDEO,
                uiConfigNote("动作替换", "上传主体素材和动作参考，让人物或角色跟随参考动作。", "effect"),
                null,
                "Subject media: {{sourceVideoUrl}}\nReference motion: {{referenceVideoUrl}}\nPreserve identity and transfer motion naturally.",
                handlerConfig("video", "motion_transfer", "effect", "VIDEO_GENERATION"),
                34,
                List.of(
                        field("sourceVideoUrl", "主体视频", "file", "上传需要替换动作的主体素材", null, true, 1),
                        field("referenceVideoUrl", "动作参考", "file", "上传动作参考视频", null, true, 2),
                        field("motionMode", "动作模式", "radio", null, options("全身动作", "半身动作", "面部表情"), false, 3),
                        field("duration", "视频时长", "radio", null, options("5", "10", "15"), false, 4)
                )
        );
        ensureDigitalHumanTemplate("digital_human_presenter", "数字人口播", "生成通用口播型数字人视频。", 40);
        ensureDigitalHumanTemplate("product_avatar_video", "商品数字人", "生成商品讲解、导购和种草场景数字人视频。", 41);
        ensureDigitalHumanTemplate("medical_avatar_video", "健康医疗数字人", "生成健康科普、医疗产品介绍场景数字人视频。", 42);
        ensureDigitalHumanTemplate("education_avatar_video", "教育讲解数字人", "生成课程讲解、知识科普场景数字人视频。", 43);
    }

    private void ensureDigitalHumanTemplate(String code, String name, String subtitle, int sortOrder) {
        ensurePolloTemplate(
                code,
                name,
                ToolType.VIDEO_GENERATION,
                ExecutionHandler.DIGITAL_HUMAN,
                ToolModality.MULTIMODAL,
                ToolModality.VIDEO,
                uiConfigNote(name, subtitle, "effect"),
                "你是数字人视频生成助手，负责把运营配置和用户输入转成稳定、自然、适合口播的数字人生成参数。",
                "Avatar: {{referenceImageUrl}}\nScript: {{script}}\nVoice: {{voiceStyle}}\nScene: {{scene}}\nDuration: {{duration}}\nAspect ratio: {{aspectRatio}}\nBrand/product: {{brandName}}",
                handlerConfig("digitalHuman", code, "effect", "DIGITAL_HUMAN"),
                sortOrder,
                List.of(
                        field("referenceImageUrl", "数字人形象", "image", "上传或填写数字人形象参考图", null, false, 1),
                        field("script", "口播脚本", "textarea", "输入数字人口播脚本", null, true, 2),
                        field("voiceStyle", "声音风格", "radio", null, options("专业讲解", "亲和导购", "知识博主", "活力主播"), false, 3),
                        field("scene", "出镜场景", "radio", null, options("直播间", "产品展示台", "办公室", "纯色演播室"), false, 4),
                        field("duration", "视频时长", "radio", null, options("10", "30", "60"), false, 5),
                        field("aspectRatio", "视频比例", "radio", null, options("9:16", "16:9", "1:1"), false, 6),
                        field("brandName", "品牌/产品", "text", "填写品牌、产品或课程名称", null, false, 7)
                )
        );
    }

    private void ensurePolloTemplate(String code,
                                     String name,
                                     ToolType toolType,
                                     ExecutionHandler handler,
                                     ToolModality input,
                                     ToolModality output,
                                     String configNote,
                                     String systemPrompt,
                                     String userPromptTemplate,
                                     String handlerConfigJson,
                                     int sortOrder,
                                     List<TemplateFieldSeed> fields) {
        if (toolTemplateMapper.findByCode(code).isPresent()) {
            return;
        }
        insertTemplate(code, name, toolType, handler, input, output, configNote, systemPrompt,
                userPromptTemplate, handlerConfigJson, sortOrder, fields);
    }

    private void insertTextToSpeechTemplate() {
        insertTemplate(
                "text_to_speech_default",
                "Text to speech",
                ToolType.TEXT_TO_SPEECH,
                ExecutionHandler.TEXT_TO_SPEECH,
                ToolModality.TEXT,
                ToolModality.AUDIO,
                "TTS tool: input text, voice, speed, audio format, and optional language boost settings.",
                null,
                null,
                5,
                List.of(
                        field("text", "Text", "textarea", "Text to synthesize", null, true, 1),
                        field("voice", "Voice", "text", "Provider voice id, for example English_expressive_narrator", null, false, 2),
                        field("speed", "Speed", "number", "1.0", null, false, 3),
                        field("format", "Audio format", "select", null,
                                options("mp3", "wav", "flac"), false, 4),
                        field("languageBoost", "Language boost", "select", null,
                                options("auto", "Chinese", "English", "Japanese", "Korean"), false, 5)
                )
        );
    }

    private void ensureMusicGenerationTemplate() {
        var existing = toolTemplateMapper.findByCode("music_generation_default");
        if (existing.isEmpty()) {
            insertMusicGenerationTemplate();
            return;
        }
        upgradeMusicGenerationTemplateFields(existing.get().getId());
    }

    private void upgradeMusicGenerationTemplateFields(Long templateId) {
        var existingKeys = toolTemplateFieldMapper.findByTemplateId(templateId).stream()
                .map(ToolTemplateField::getFieldKey)
                .collect(java.util.stream.Collectors.toSet());
        if (existingKeys.contains("personaId") && existingKeys.contains("generationType")) {
            return;
        }
        toolTemplateFieldMapper.deleteByTemplateId(templateId);
        insertMusicGenerationTemplateFields(templateId);
    }

    private void insertMusicGenerationTemplate() {
        insertTemplate(
                "music_generation_default",
                "Music generation",
                ToolType.MUSIC_GENERATION,
                ExecutionHandler.MUSIC_GENERATION,
                ToolModality.TEXT,
                ToolModality.AUDIO,
                "Music generation tool: text prompt to two generated songs via a polling worker provider.",
                null,
                null,
                6,
                musicGenerationTemplateFields()
        );
    }

    private void insertMusicGenerationTemplateFields(Long templateId) {
        for (TemplateFieldSeed seed : musicGenerationTemplateFields()) {
            ToolTemplateField field = new ToolTemplateField();
            field.setTemplateId(templateId);
            field.setFieldKey(seed.fieldKey());
            field.setFieldName(seed.fieldName());
            field.setFieldType(seed.fieldType());
            field.setPlaceholder(seed.placeholder());
            field.setOptionsJson(seed.optionsJson());
            field.setRequired(seed.required());
            field.setSortOrder(seed.sortOrder());
            field.setStatus("ACTIVE");
            toolTemplateFieldMapper.insert(field);
        }
    }

    private List<TemplateFieldSeed> musicGenerationTemplateFields() {
        return List.of(
                field("generationType", "任务类型", "radio", "文生音乐或上传音频翻唱",
                        "{\"uiTier\":\"all\",\"options\":[{\"label\":\"文生音乐\",\"value\":\"generate\"},{\"label\":\"上传翻唱\",\"value\":\"upload_cover\"}],\"defaultValue\":\"generate\"}",
                        false, 1),
                field("referenceAudio", "参考音频", "file", "上传 mp3/wav 等，时长 ≤8 分钟（V4_5ALL ≤1 分钟）",
                        "{\"uiTier\":\"all\",\"uiGroup\":\"reference\",\"uiGroupLabel\":\"参考音频\",\"visibleWhen\":{\"generationType\":[\"upload_cover\"]},\"accept\":\"audio/*\"}",
                        false, 2),
                field("customMode", "创作模式", "radio", "常规：仅描述想法；高级：自定义歌词、风格与标题",
                        "{\"options\":[{\"label\":\"常规\",\"value\":\"false\"},{\"label\":\"高级\",\"value\":\"true\"}],\"defaultValue\":\"false\"}",
                        false, 3),
                field("prompt", "音乐描述 / 歌词", "textarea", "常规：描述主题、情绪（≤500字）；高级+非纯音乐：作为歌词（≤5000字）",
                        "{\"core\":true,\"uiTier\":\"all\",\"uiGroup\":\"lyrics\",\"uiGroupLabel\":\"歌词\",\"maxLength\":500,\"maxLengthByModel\":{\"V4\":3000,\"default\":5000},\"visibleWhen\":{\"instrumental\":[\"false\"]}}",
                        true, 4),
                field("instrumental", "纯音乐（无人声）", "checkbox", null,
                        "{\"uiTier\":\"all\",\"uiGroup\":\"lyrics\",\"defaultValue\":false}",
                        false, 5),
                field("style", "风格 / 标签", "textarea", "例如：cinematic pop, warm piano, Mandarin ballad",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"styles\",\"uiGroupLabel\":\"风格\",\"visibleWhen\":{\"customMode\":[\"true\"]},\"maxLength\":1000,\"maxLengthByModel\":{\"V4\":200,\"default\":1000}}",
                        false, 6),
                field("title", "歌曲标题", "text", "高级模式下必填",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"meta\",\"uiGroupLabel\":\"基本信息\",\"visibleWhen\":{\"customMode\":[\"true\"]},\"maxLength\":100,\"maxLengthByModel\":{\"V4\":80,\"V4_5ALL\":80,\"default\":100}}",
                        false, 7),
                field("model", "Suno 模型", "select", null,
                        "{\"uiTier\":\"all\",\"uiGroup\":\"meta\",\"options\":[{\"label\":\"V5.5（推荐）\",\"value\":\"V5_5\"},{\"label\":\"V5\",\"value\":\"V5\"},{\"label\":\"V4.5+\",\"value\":\"V4_5PLUS\"},{\"label\":\"V4.5-all\",\"value\":\"V4_5ALL\"},{\"label\":\"V4.5\",\"value\":\"V4_5\"},{\"label\":\"V4\",\"value\":\"V4\"}],\"defaultValue\":\"V5_5\"}",
                        false, 8),
                field("negativeTags", "负面标签", "text", "例如：noise, low quality, distorted vocal",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"uiGroupLabel\":\"更多选项\",\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 9),
                field("vocalGender", "人声性别", "radio", null,
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"options\":[{\"label\":\"自动\",\"value\":\"auto\"},{\"label\":\"男声\",\"value\":\"m\"},{\"label\":\"女声\",\"value\":\"f\"}],\"defaultValue\":\"auto\",\"visibleWhen\":{\"customMode\":[\"true\"],\"instrumental\":[\"false\"]}}",
                        false, 10),
                field("styleWeight", "风格权重", "slider", "0.65",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"slider\":{\"min\":0,\"max\":1,\"step\":0.01},\"defaultValue\":0.65,\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 11),
                field("weirdnessConstraint", "创意发散", "slider", "0.65",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"slider\":{\"min\":0,\"max\":1,\"step\":0.01},\"defaultValue\":0.65,\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 12),
                field("audioWeight", "音频权重", "slider", "翻唱时控制原曲保留程度；0.65",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"slider\":{\"min\":0,\"max\":1,\"step\":0.01},\"defaultValue\":0.65,\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 13),
                field("personaId", "Persona / 声线 ID", "text", "Generate Persona 或 Suno Voice 返回的 ID",
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 14),
                field("personaModel", "Persona 类型", "select", null,
                        "{\"uiTier\":\"advanced\",\"uiGroup\":\"more\",\"options\":[{\"label\":\"风格 Persona\",\"value\":\"style_persona\"},{\"label\":\"Voice Persona（V5/V5.5）\",\"value\":\"voice_persona\"}],\"defaultValue\":\"style_persona\",\"visibleWhen\":{\"customMode\":[\"true\"]}}",
                        false, 15)
        );
    }

    private void insertTemplate(String code,
                                String name,
                                ToolType toolType,
                                ExecutionHandler handler,
                                ToolModality input,
                                ToolModality output,
                                String configNote,
                                String systemPrompt,
                                String userPromptTemplate,
                                int sortOrder,
                                List<TemplateFieldSeed> fields) {
        insertTemplate(code, name, toolType, handler, input, output, configNote, systemPrompt,
                userPromptTemplate, null, sortOrder, fields);
    }

    private void insertTemplate(String code,
                                String name,
                                ToolType toolType,
                                ExecutionHandler handler,
                                ToolModality input,
                                ToolModality output,
                                String configNote,
                                String systemPrompt,
                                String userPromptTemplate,
                                String handlerConfigJson,
                                int sortOrder,
                                List<TemplateFieldSeed> fields) {
        ToolTemplate template = new ToolTemplate();
        template.setTemplateCode(code);
        template.setTemplateName(name);
        template.setToolType(toolType.name());
        template.setExecutionHandler(handler.name());
        template.setInputModality(input.name());
        template.setOutputModality(output.name());
        template.setConfigNote(configNote);
        template.setDefaultSystemPrompt(systemPrompt);
        template.setDefaultUserPromptTemplate(userPromptTemplate);
        template.setDefaultOutputFormat("MARKDOWN");
        template.setHandlerConfigJson(handlerConfigJson);
        template.setStatus("ACTIVE");
        template.setSortOrder(sortOrder);
        template.setSystemTemplate(true);
        toolTemplateMapper.insert(template);
        for (TemplateFieldSeed seed : fields) {
            ToolTemplateField field = new ToolTemplateField();
            field.setTemplateId(template.getId());
            field.setFieldKey(seed.fieldKey());
            field.setFieldName(seed.fieldName());
            field.setFieldType(seed.fieldType());
            field.setPlaceholder(seed.placeholder());
            field.setOptionsJson(seed.optionsJson());
            field.setRequired(seed.required());
            field.setSortOrder(seed.sortOrder());
            field.setStatus("ACTIVE");
            toolTemplateFieldMapper.insert(field);
        }
    }

    private static String handlerConfig(String toolKind, String abilityCode, String displayPreset, String requiredCapability) {
        return """
                {"toolKind":"%s","abilityCode":"%s","displayPreset":"%s","requiredCapability":"%s","defaultFrontendStyle":{"primaryColor":"#ff2f6d","mediaDisplayMode":"%s"},"fieldGroups":[{"code":"media","title":"素材上传"},{"code":"options","title":"效果参数"},{"code":"execution","title":"执行配置"}]}
                """.formatted(
                jsonEscape(toolKind),
                jsonEscape(abilityCode),
                jsonEscape(displayPreset),
                jsonEscape(requiredCapability),
                jsonEscape(displayPreset)
        ).trim();
    }

    private static String uiConfigNote(String title, String subtitle, String mediaDisplayMode) {
        return """
                Pollo 风格 %s 能力模板。前端展示配置可在工具配置页覆盖。

                <!-- ai-tool-ui:{"primaryColor":"#ff2f6d","welcomeMessage":"","mediaDisplayMode":"%s","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","heroTitle":"%s","heroSubtitle":"%s","demoThumbnails":[],"useCases":["社媒内容创作","电商素材处理","品牌视觉统一"],"steps":["上传素材","选择效果参数","点击生成"],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->
                """.formatted(
                title,
                jsonEscape(mediaDisplayMode),
                jsonEscape(title),
                jsonEscape(subtitle)
        ).trim();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static TemplateFieldSeed field(String key, String name, String type, String placeholder,
                                           String optionsJson, boolean required, int sortOrder) {
        return new TemplateFieldSeed(key, name, type, placeholder, optionsJson, required, sortOrder);
    }

    private static String options(String... values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"label\":\"").append(values[i]).append("\",\"value\":\"").append(values[i]).append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private static String imageAspectRatioOptions() {
        return "[{\"label\":\"智能\",\"value\":\"auto\"},{\"label\":\"9:16\",\"value\":\"9:16\"},{\"label\":\"2:3\",\"value\":\"2:3\"},{\"label\":\"3:4\",\"value\":\"3:4\"},{\"label\":\"1:1\",\"value\":\"1:1\"},{\"label\":\"4:3\",\"value\":\"4:3\"},{\"label\":\"3:2\",\"value\":\"3:2\"},{\"label\":\"16:9\",\"value\":\"16:9\"},{\"label\":\"21:9\",\"value\":\"21:9\"}]";
    }

    private static String videoAspectRatioOptions() {
        return "[{\"label\":\"智能\",\"value\":\"auto\"},{\"label\":\"16:9\",\"value\":\"16:9\"},{\"label\":\"9:16\",\"value\":\"9:16\"},{\"label\":\"1:1\",\"value\":\"1:1\"}]";
    }

    private record TemplateFieldSeed(
            String fieldKey,
            String fieldName,
            String fieldType,
            String placeholder,
            String optionsJson,
            boolean required,
            int sortOrder
    ) {
    }

    private void ensureTable(String tableName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, tableName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure table " + tableName, exception);
        }
    }

    private void ensureColumn(String tableName, String columnName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!columnExists(connection, tableName, columnName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure column " + tableName + "." + columnName, exception);
        }
    }

    private void executeSql(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to execute SQL", exception);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        for (String candidate : List.of(tableName, tableName.toUpperCase())) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, candidate, null)) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        for (String table : List.of(tableName, tableName.toUpperCase())) {
            for (String column : List.of(columnName, columnName.toUpperCase())) {
                try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

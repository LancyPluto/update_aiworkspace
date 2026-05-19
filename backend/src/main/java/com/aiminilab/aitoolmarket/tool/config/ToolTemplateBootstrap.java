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
                        field("aspectRatio", "画面比例", "radio", null,
                                options("1:1", "4:3", "3:4", "16:9", "9:16"), true, 2),
                        field("style", "风格", "select", null,
                                options("写实", "电商", "插画", "动漫", "极简", "国潮"), false, 3),
                        field("count", "生成数量", "number", "例如 1", null, false, 4),
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
                        field("aspectRatio", "视频比例", "radio", null,
                                options("16:9", "9:16", "1:1"), true, 2),
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
                        field("aspectRatio", "画面比例", "select", "选择视频比例",
                                options("16:9 横屏", "9:16 竖屏", "1:1 方形"), true, 5),
                        field("duration", "视频时长要求", "select",
                                "选择期望成片时长",
                                options("5 秒", "10 秒", "15 秒", "30 秒", "60 秒"), true, 6),
                        field("brandName", "品牌/产品", "text", "例如：澄光实验室补水精华", null, false, 7),
                        field("visualRequirements", "画面要求", "textarea", "例如：明亮干净、人物半身出镜", null, false, 8),
                        field("negativePrompt", "负面提示词", "textarea", "例如：画面变形、字幕错乱", null, false, 9)
                )
        );
        insertTextToSpeechTemplate();
    }

    private void ensureTextToSpeechTemplate() {
        if (toolTemplateMapper.findByCode("text_to_speech_default").isPresent()) {
            return;
        }
        insertTextToSpeechTemplate();
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

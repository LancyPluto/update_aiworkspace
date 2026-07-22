package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilityMigrationSourceTest {

    @Test
    void runtimeNormalizationTrimsUppercasesAndDeduplicatesLegacyValues() {
        String legacy = "[\" image_generation \",\"DIGITAL_HUMAN\","
                + "\"IMAGE_GENERATION\",\" digital_human \"]";

        assertThat(DataInitializer.normalizeLegacyCapabilities(legacy, false))
                .containsExactly("IMAGE_GENERATION");
        assertThat(DataInitializer.normalizeLegacyCapabilities(legacy, true))
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void runtimeBackfillsEveryMissingOrUnusableToolCapabilityArray() {
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities(null, true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("   ", true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("not-json", true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("{}", true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("[]", true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("[null, \"  \"]", true))).isTrue();
        assertThat(DataInitializer.requiresToolCapabilityBackfill(
                DataInitializer.normalizeLegacyCapabilities("[\"TEXT_GENERATION\"]", true))).isFalse();
    }

    @Test
    void runtimeModelNormalizationMapsOnlyTheLegacyMarkerAndPreservesOtherValues() {
        String legacy = "[\" image_generation \",\"DIGITAL_HUMAN\",\"IMAGE_GENERATION\"]";

        assertThat(DataInitializer.normalizeLegacyModelCapabilities(legacy, "seedance"))
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
        assertThat(DataInitializer.normalizeLegacyModelCapabilities(legacy, "siliconflow_images"))
                .containsExactly("IMAGE_GENERATION");
        assertThat(DataInitializer.normalizeLegacyModelCapabilities(legacy, "other_provider"))
                .containsExactly("IMAGE_GENERATION");
    }

    @Test
    void digitalHumanRuntimeOnlyAcceptsImplementedVideoProviders() {
        assertThat(DataInitializer.isDigitalHumanVideoProvider("seedance")).isTrue();
        assertThat(DataInitializer.isDigitalHumanVideoProvider(" InfiniteTalk ")).isTrue();
        assertThat(DataInitializer.isDigitalHumanVideoProvider("kling_video")).isFalse();
        assertThat(DataInitializer.isDigitalHumanVideoProvider("agnes_video")).isFalse();
        assertThat(DataInitializer.isDigitalHumanVideoProvider(null)).isFalse();
    }

    @Test
    void migrationAddsAndBackfillsToolModelCapabilities() throws Exception {
        String migration = Files.readString(Path.of("../sql/111_ai_tool_required_model_capabilities.sql"));

        assertThat(migration).contains("column_name = 'required_model_capabilities'");
        assertThat(migration).contains("ADD COLUMN required_model_capabilities TEXT NULL");
        assertThat(migration).contains("WHEN 'DIGITAL_HUMAN' THEN '[\"VIDEO_GENERATION\"]'");
        assertThat(migration).contains("'[\"TEXT_GENERATION\",\"VISION_INPUT\"]'");
        assertThat(migration).contains("JSON_TABLE");
        assertThat(migration).contains("SELECT DISTINCT source.id");
        assertThat(migration).contains("UPPER(TRIM(capability_row.raw_capability))");
        assertThat(migration).contains("OR JSON_VALID(required_model_capabilities) = 0");
        assertThat(migration).contains("OR JSON_TYPE(");
        assertThat(migration).contains(") <> 'ARRAY'");
        assertThat(migration).contains("OR JSON_LENGTH(");
    }

    @Test
    void runtimeMigratesDigitalHumanTemplateSlotCapabilityIdempotently() {
        String legacy = "{\"requiredCapability\":\" digital_human \",\"abilityCode\":\"avatar\"}";

        String migrated = DataInitializer.normalizeDigitalHumanTemplateHandlerConfig(legacy);

        assertThat(migrated).contains("\"requiredCapability\":\"VIDEO_GENERATION\"");
        assertThat(migrated).contains("\"abilityCode\":\"avatar\"");
        assertThat(DataInitializer.normalizeDigitalHumanTemplateHandlerConfig(migrated))
                .isEqualTo(migrated);
        assertThat(DataInitializer.normalizeDigitalHumanTemplateHandlerConfig(
                "{\"requiredCapability\":\"TEXT_GENERATION\"}"
        )).isEqualTo("{\"requiredCapability\":\"TEXT_GENERATION\"}");
    }

    @Test
    void migrationUpdatesLegacyDigitalHumanTemplateSlotCapability() throws Exception {
        String migration = Files.readString(Path.of("../sql/111_ai_tool_required_model_capabilities.sql"));

        assertThat(migration).contains("UPDATE tool_templates");
        assertThat(migration).contains("JSON_SET(handler_config_json, '$.requiredCapability', 'VIDEO_GENERATION')");
        assertThat(migration).contains("JSON_EXTRACT(handler_config_json, '$.requiredCapability')");
        assertThat(migration).contains("UPPER(COALESCE(execution_handler, '')) = 'DIGITAL_HUMAN'");
    }

    @Test
    void migrationRepairsSeedanceAndRemovesDigitalHumanModelCapability() throws Exception {
        String migration = Files.readString(Path.of("../sql/111_ai_tool_required_model_capabilities.sql"));

        assertThat(migration).contains("provider = 'volcengine_images'");
        assertThat(migration).contains("LOWER(model_name) LIKE '%seedance%'");
        assertThat(migration).contains("provider = 'seedance'");
        assertThat(migration).contains("JSON_ARRAY('VIDEO_GENERATION')");
        assertThat(migration).contains("execution_task = 'video_generation'");
        assertThat(migration).contains("JSON_TABLE");
        assertThat(migration).contains("JSON_TYPE(IF(JSON_VALID(source.capabilities)");
        assertThat(migration).contains("JSON_TYPE(IF(JSON_VALID(source.capabilities_json)");
        assertThat(migration).contains("JSON_VALID(source.required_model_capabilities)");
        assertThat(migration).contains("JSON_ARRAY_APPEND(capabilities, '$', 'VIDEO_GENERATION')");
        assertThat(migration).contains("JSON_ARRAY_APPEND(capabilities_json, '$', 'IMAGE_GENERATION')");
        assertThat(migration.split(Pattern.quote(
                "JSON_TYPE(IF(JSON_VALID(capabilities), capabilities, JSON_OBJECT())) = 'ARRAY'"
        ), -1)).hasSize(3);
        assertThat(migration.split(Pattern.quote(
                "JSON_TYPE(IF(JSON_VALID(capabilities_json), capabilities_json, JSON_OBJECT())) = 'ARRAY'"
        ), -1)).hasSize(3);
        assertThat(migration).contains("JSON_CONTAINS");
        assertThat(migration).contains("LEFT JOIN model_provider_metadata metadata");
        assertThat(migration).contains("JSON_LENGTH(");
        assertThat(migration).contains(
                "LOWER(COALESCE(model.provider, '')) IN ('seedance', 'infinitetalk')"
        );
        assertThat(migration).contains("model_config_id = NULL");
    }

    @Test
    void runtimeAndTestSchemasExposeRequiredCapabilitiesColumn() throws Exception {
        String initializer = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));
        String schema = Files.readString(Path.of("src/test/resources/schema-test.sql"));

        assertThat(initializer).contains(
                "ensureColumn(\"ai_tools\", \"required_model_capabilities\", "
                        + "\"ALTER TABLE ai_tools ADD COLUMN required_model_capabilities TEXT NULL\")"
        );
        assertThat(initializer).contains(
                "providerColumn == null && requiresToolCapabilityBackfill(normalized)"
        );
        assertThat(initializer).contains("normalizeDigitalHumanTemplateCapabilities();");
        assertThat(schema).contains("required_model_capabilities TEXT");

        int providerMetadataTable = initializer.indexOf("ensureTable(\"model_provider_metadata\"");
        int providerSeed = initializer.indexOf("seedModelProviderMetadata();");
        int templateSeed = initializer.indexOf("toolTemplateBootstrap.ensureSchemaAndSeed();");
        int agnesToolSeed = initializer.indexOf("seedAgnesTextToVideoTool();");
        int imageToolSeed = initializer.indexOf("seedDefaultTextToImageTool();");
        int normalization = initializer.indexOf("normalizeToolAndModelCapabilities();");
        assertThat(providerMetadataTable).isGreaterThanOrEqualTo(0);
        assertThat(normalization).isGreaterThan(providerSeed);
        assertThat(normalization).isGreaterThan(templateSeed);
        assertThat(normalization).isGreaterThan(agnesToolSeed);
        assertThat(normalization).isGreaterThan(imageToolSeed);
    }

    @Test
    void providerManifestDoesNotExposeDigitalHumanAsAModelCapability() throws Exception {
        String manifest = Files.readString(Path.of("src/main/resources/model-providers.yml"));

        assertThat(manifest).doesNotContain("- DIGITAL_HUMAN");
    }

    @Test
    void digitalHumanTemplateUsesVideoAsItsSingleSlotCapability() throws Exception {
        String bootstrap = Files.readString(Path.of(
                "src/main/java/com/aiminilab/aitoolmarket/tool/config/ToolTemplateBootstrap.java"
        ));

        assertThat(bootstrap).contains(
                "handlerConfig(\"digitalHuman\", code, \"effect\", \"VIDEO_GENERATION\")"
        );
        assertThat(bootstrap).doesNotContain(
                "handlerConfig(\"digitalHuman\", code, \"effect\", \"DIGITAL_HUMAN\")"
        );
    }
}

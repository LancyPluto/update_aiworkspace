package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingVersionMapper;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRouterSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentMemorySettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRuntimeSettings;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountMigrationService;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.aiminilab.aitoolmarket.tool.config.ToolTemplateBootstrap;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
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

    public DataInitializer(UserMapper userMapper, ToolCategoryMapper toolCategoryMapper,
                           SystemSettingMapper systemSettingMapper, SystemSettingVersionMapper systemSettingVersionMapper,
                           PasswordEncoder passwordEncoder,
                           JdbcTemplate jdbcTemplate, ToolTemplateBootstrap toolTemplateBootstrap,
                           ModelVendorAccountMigrationService modelVendorAccountMigrationService,
                           ModelProviderRegistry modelProviderRegistry,
                           AppProperties appProperties) {
        this.userMapper = userMapper;
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
        seedModelProviderMetadata();
        modelVendorAccountMigrationService.migrateIfNeeded();
        seedGptImageApiKeysFromEnv();
        toolTemplateBootstrap.ensureSchemaAndSeed();
        normalizeGptImageToolFieldOptions();
        createUserIfAbsent("admin", "123456", "Admin", UserType.ADMIN);
        createUserIfAbsent("user1", "123456", "User One", UserType.USER);
        toolCategoryMapper.ensureDefaultCategory();
        toolCategoryMapper.retireLegacyCategories();
        systemSettingMapper.ensureTable();
        systemSettingVersionMapper.ensureTable();
        seedAgentPromptSettings();
    }

    private void seedAgentPromptSettings() {
        seedSettingDefaults(AgentPromptSettings.defaults(), "agent", "Agent prompt setting");
        seedSettingDefaults(AgentRouterSettings.defaults(), "agent", "Agent router setting");
        seedSettingDefaults(AgentMemorySettings.defaults(), "agent", "Agent memory setting");
        seedSettingDefaults(AgentRuntimeSettings.defaults(), "agent", "Agent runtime setting");
    }

    private void seedSettingDefaults(java.util.Map<String, String> defaults, String group, String description) {
        defaults.forEach((key, value) -> systemSettingMapper.insertIfAbsent(key, value, group, description));
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private void ensureSchemaCompatibility() {
        ensureColumn("users", "avatar_url", "ALTER TABLE users ADD COLUMN avatar_url VARCHAR(512) NULL");
        ensureColumn("users", "bio", "ALTER TABLE users ADD COLUMN bio VARCHAR(280) NULL");
        ensureColumn("users", "auto_publish_assets", "ALTER TABLE users ADD COLUMN auto_publish_assets TINYINT NOT NULL DEFAULT 1");
        ensureColumn("users", "prompt_public_by_default", "ALTER TABLE users ADD COLUMN prompt_public_by_default TINYINT NOT NULL DEFAULT 0");
        ensureColumn("ai_tools", "model_config_id", "ALTER TABLE ai_tools ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("ai_tools", "tool_type", "ALTER TABLE ai_tools ADD COLUMN tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION'");
        ensureColumn("ai_tools", "input_modality", "ALTER TABLE ai_tools ADD COLUMN input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "output_modality", "ALTER TABLE ai_tools ADD COLUMN output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "config_note", "ALTER TABLE ai_tools ADD COLUMN config_note TEXT NULL");
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
        ensureColumn("ai_tasks", "model_snapshot_json", "ALTER TABLE ai_tasks ADD COLUMN model_snapshot_json TEXT NULL");
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
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_user_upload_assets_user_kind (user_id, asset_kind, status, id),
                  KEY idx_user_upload_assets_user_url (user_id, url(255))
                )
                """);
        ensureColumn("agent_model_configs", "display_name", "ALTER TABLE agent_model_configs ADD COLUMN display_name VARCHAR(128) NULL");
        ensureColumn("agent_model_configs", "config_code", "ALTER TABLE agent_model_configs ADD COLUMN config_code VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "console_url", "ALTER TABLE agent_model_configs ADD COLUMN console_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "balance_url", "ALTER TABLE agent_model_configs ADD COLUMN balance_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "docs_url", "ALTER TABLE agent_model_configs ADD COLUMN docs_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "extra_auth_json", "ALTER TABLE agent_model_configs ADD COLUMN extra_auth_json TEXT NULL");
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
                  ('suno', 'Suno', 'suno', 55, 1)
                ON DUPLICATE KEY UPDATE
                  vendor_label = VALUES(vendor_label),
                  icon_asset = VALUES(icon_asset),
                  sort_order = VALUES(sort_order),
                  enabled = VALUES(enabled),
                  updated_at = CURRENT_TIMESTAMP
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
                  balance_query_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
                  balance_amount DECIMAL(18,4) NULL,
                  balance_currency VARCHAR(8) NULL DEFAULT 'CNY',
                  balance_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  balance_low_threshold DECIMAL(18,4) NULL,
                  balance_updated_at DATETIME NULL,
                  balance_error_message VARCHAR(512) NULL,
                  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  enabled TINYINT NOT NULL DEFAULT 1,
                  is_deleted TINYINT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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
        ensureIndex(
                "credit_recharge_orders",
                "uk_recharge_user_idem",
                "CREATE UNIQUE INDEX uk_recharge_user_idem ON credit_recharge_orders(user_id, idempotency_key)"
        );
        executeSql("""
                UPDATE credit_recharge_packages
                SET status = 'INACTIVE', updated_at = NOW()
                WHERE package_code = 'test_1000'
                  AND status = 'ACTIVE'
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
        ensureColumn("agent_files", "attached_run_id", "ALTER TABLE agent_files ADD COLUMN attached_run_id BIGINT NULL");
        ensureIndex("agent_files", "idx_agent_files_attached_run", "CREATE INDEX idx_agent_files_attached_run ON agent_files(session_id, attached_run_id, id)");
        ensureColumn("agent_messages", "status", "ALTER TABLE agent_messages ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("agent_messages", "superseded_at", "ALTER TABLE agent_messages ADD COLUMN superseded_at DATETIME NULL");
        ensureColumn("agent_messages", "edited_at", "ALTER TABLE agent_messages ADD COLUMN edited_at DATETIME NULL");
        ensureIndex("agent_messages", "idx_agent_messages_session_active", "CREATE INDEX idx_agent_messages_session_active ON agent_messages(session_id, status, id)");
        ensureColumn("agent_runs", "model_config_id", "ALTER TABLE agent_runs ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("agent_runs", "parent_run_id", "ALTER TABLE agent_runs ADD COLUMN parent_run_id BIGINT NULL");
        ensureColumn("agent_runs", "source_user_message_id", "ALTER TABLE agent_runs ADD COLUMN source_user_message_id BIGINT NULL");
        ensureColumn("agent_runs", "context_snapshot_id", "ALTER TABLE agent_runs ADD COLUMN context_snapshot_id BIGINT NULL");
        ensureColumn("agent_runs", "client_request_id", "ALTER TABLE agent_runs ADD COLUMN client_request_id VARCHAR(64) NULL");
        ensureColumn("agent_runs", "preferred_tool_code", "ALTER TABLE agent_runs ADD COLUMN preferred_tool_code VARCHAR(64) NULL");
        ensureIndex("agent_runs", "uk_agent_runs_user_client", "CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs(user_id, client_request_id)");
        ensureIndex("agent_runs", "idx_agent_runs_session_user_id", "CREATE INDEX idx_agent_runs_session_user_id ON agent_runs(session_id, user_id, id)");
        ensureIndex("agent_runs", "idx_agent_runs_model_config", "CREATE INDEX idx_agent_runs_model_config ON agent_runs(model_config_id)");
        ensureIndex("agent_runs", "idx_agent_runs_context_snapshot", "CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs(context_snapshot_id)");
        ensureColumn("agent_tool_calls", "task_id", "ALTER TABLE agent_tool_calls ADD COLUMN task_id BIGINT NULL");
        ensureIndex("agent_tool_calls", "idx_agent_tool_calls_task_id", "CREATE INDEX idx_agent_tool_calls_task_id ON agent_tool_calls(task_id)");
        ensureIndex("agent_tool_calls", "idx_agent_tool_calls_context_recent", "CREATE INDEX idx_agent_tool_calls_context_recent ON agent_tool_calls(user_id, status, id)");
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

    private void ensureColumn(String tableName, String columnName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!columnExists(connection, tableName, columnName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure database column " + tableName + "." + columnName, exception);
        }
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
                [{"label":"智能","value":"auto"},{"label":"9:16","value":"1024x1536"},{"label":"2:3","value":"1024x1536"},{"label":"3:4","value":"1024x1536"},{"label":"1:1","value":"1024x1024"},{"label":"4:3","value":"1536x1024"},{"label":"3:2","value":"1536x1024"},{"label":"16:9","value":"1536x1024"}]
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
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setUserType(userType.name());
        user.setStatus(UserStatus.ACTIVE.name());
        userMapper.insertAndReturnId(user);
    }
}

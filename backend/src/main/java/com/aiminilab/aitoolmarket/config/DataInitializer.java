package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
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
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final ToolTemplateBootstrap toolTemplateBootstrap;

    public DataInitializer(UserMapper userMapper, ToolCategoryMapper toolCategoryMapper,
                           SystemSettingMapper systemSettingMapper, PasswordEncoder passwordEncoder,
                           JdbcTemplate jdbcTemplate, ToolTemplateBootstrap toolTemplateBootstrap) {
        this.userMapper = userMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.systemSettingMapper = systemSettingMapper;
        this.passwordEncoder = passwordEncoder;
        this.dataSource = jdbcTemplate.getDataSource();
        this.toolTemplateBootstrap = toolTemplateBootstrap;
    }

    @Override
    public void run(String... args) {
        ensureSchemaCompatibility();
        toolTemplateBootstrap.ensureSchemaAndSeed();
        createUserIfAbsent("admin", "123456", "Admin", UserType.ADMIN);
        createUserIfAbsent("user1", "123456", "User One", UserType.USER);
        toolCategoryMapper.ensureDefaultCategory();
        systemSettingMapper.ensureTable();
        seedAgentPromptSettings();
    }

    private void seedAgentPromptSettings() {
        systemSettingMapper.insertIfAbsent(
                AgentPromptSettings.SYSTEM_PROMPT_KEY,
                AgentPromptSettings.DEFAULT_SYSTEM_PROMPT,
                "agent",
                "Agent normal chat system prompt"
        );
        systemSettingMapper.insertIfAbsent(
                AgentPromptSettings.DEEP_AGENTS_SYSTEM_PROMPT_KEY,
                AgentPromptSettings.DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT,
                "agent",
                "Agent deep-agents runtime system prompt"
        );
    }

    private void ensureSchemaCompatibility() {
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
        ensureColumn("agent_model_configs", "is_default", "ALTER TABLE agent_model_configs ADD COLUMN is_default TINYINT NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "is_deleted", "ALTER TABLE agent_model_configs ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0");
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
                  package_id BIGINT NOT NULL,
                  credits INT NOT NULL,
                  price_amount DECIMAL(18,2) NOT NULL,
                  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
                  payment_channel VARCHAR(32) NOT NULL DEFAULT 'MOCK',
                  status VARCHAR(32) NOT NULL DEFAULT 'WAITING_PAYMENT',
                  status_reason VARCHAR(255),
                  pay_url VARCHAR(512),
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
        ensureIndex(
                "credit_recharge_orders",
                "uk_recharge_user_idem",
                "CREATE UNIQUE INDEX uk_recharge_user_idem ON credit_recharge_orders(user_id, idempotency_key)"
        );
        executeSql("""
                INSERT INTO credit_recharge_packages (
                  package_code, package_name, credits, price_amount, currency,
                  validity_days, benefits_json, recommended, sort_order, status
                )
                SELECT 'test_1000', 'Test credits', 1000, 10.00, 'CNY',
                       30, '["Priority queue"]', 1, 10, 'ACTIVE'
                WHERE NOT EXISTS (
                  SELECT 1 FROM credit_recharge_packages WHERE package_code = 'test_1000'
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
        ensureColumn("agent_files", "attached_run_id", "ALTER TABLE agent_files ADD COLUMN attached_run_id BIGINT NULL");
        ensureIndex("agent_files", "idx_agent_files_attached_run", "CREATE INDEX idx_agent_files_attached_run ON agent_files(session_id, attached_run_id, id)");
        ensureColumn("agent_messages", "status", "ALTER TABLE agent_messages ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("agent_messages", "superseded_at", "ALTER TABLE agent_messages ADD COLUMN superseded_at DATETIME NULL");
        ensureColumn("agent_runs", "model_config_id", "ALTER TABLE agent_runs ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("agent_runs", "parent_run_id", "ALTER TABLE agent_runs ADD COLUMN parent_run_id BIGINT NULL");
        ensureColumn("agent_runs", "source_user_message_id", "ALTER TABLE agent_runs ADD COLUMN source_user_message_id BIGINT NULL");
        ensureColumn("agent_runs", "context_snapshot_id", "ALTER TABLE agent_runs ADD COLUMN context_snapshot_id BIGINT NULL");
        ensureColumn("agent_runs", "client_request_id", "ALTER TABLE agent_runs ADD COLUMN client_request_id VARCHAR(64) NULL");
        ensureIndex("agent_runs", "uk_agent_runs_user_client", "CREATE UNIQUE INDEX uk_agent_runs_user_client ON agent_runs(user_id, client_request_id)");
        ensureIndex("agent_runs", "idx_agent_runs_model_config", "CREATE INDEX idx_agent_runs_model_config ON agent_runs(model_config_id)");
        ensureIndex("agent_runs", "idx_agent_runs_context_snapshot", "CREATE INDEX idx_agent_runs_context_snapshot ON agent_runs(context_snapshot_id)");
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

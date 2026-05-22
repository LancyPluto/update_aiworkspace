package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
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
    }

    private void ensureSchemaCompatibility() {
        ensureColumn("ai_tools", "model_config_id", "ALTER TABLE ai_tools ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("ai_tools", "tool_type", "ALTER TABLE ai_tools ADD COLUMN tool_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION'");
        ensureColumn("ai_tools", "input_modality", "ALTER TABLE ai_tools ADD COLUMN input_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "output_modality", "ALTER TABLE ai_tools ADD COLUMN output_modality VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
        ensureColumn("ai_tools", "config_note", "ALTER TABLE ai_tools ADD COLUMN config_note TEXT NULL");
        ensureColumn("agent_model_configs", "display_name", "ALTER TABLE agent_model_configs ADD COLUMN display_name VARCHAR(128) NULL");
        ensureColumn("agent_model_configs", "config_code", "ALTER TABLE agent_model_configs ADD COLUMN config_code VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "console_url", "ALTER TABLE agent_model_configs ADD COLUMN console_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "balance_url", "ALTER TABLE agent_model_configs ADD COLUMN balance_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "docs_url", "ALTER TABLE agent_model_configs ADD COLUMN docs_url VARCHAR(512) NULL");
        ensureColumn("agent_model_configs", "input_token_price_per_1k", "ALTER TABLE agent_model_configs ADD COLUMN input_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "output_token_price_per_1k", "ALTER TABLE agent_model_configs ADD COLUMN output_token_price_per_1k DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "input_token_price_per_1m", "ALTER TABLE agent_model_configs ADD COLUMN input_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "output_token_price_per_1m", "ALTER TABLE agent_model_configs ADD COLUMN output_token_price_per_1m DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "billing_unit", "ALTER TABLE agent_model_configs ADD COLUMN billing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN_PER_M'");
        ensureColumn("agent_model_configs", "unit_price", "ALTER TABLE agent_model_configs ADD COLUMN unit_price DECIMAL(18,8) NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "capabilities", "ALTER TABLE agent_model_configs ADD COLUMN capabilities TEXT NULL");
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
        ensureColumn("agent_messages", "status", "ALTER TABLE agent_messages ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("agent_messages", "superseded_at", "ALTER TABLE agent_messages ADD COLUMN superseded_at DATETIME NULL");
        ensureColumn("agent_runs", "parent_run_id", "ALTER TABLE agent_runs ADD COLUMN parent_run_id BIGINT NULL");
        ensureColumn("agent_runs", "source_user_message_id", "ALTER TABLE agent_runs ADD COLUMN source_user_message_id BIGINT NULL");
        ensureColumn("agent_runs", "client_request_id", "ALTER TABLE agent_runs ADD COLUMN client_request_id VARCHAR(64) NULL");
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

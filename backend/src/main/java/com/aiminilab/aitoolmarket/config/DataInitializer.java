package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
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
    private final JdbcTemplate jdbcTemplate;

    public DataInitializer(UserMapper userMapper, ToolCategoryMapper toolCategoryMapper,
                           SystemSettingMapper systemSettingMapper, PasswordEncoder passwordEncoder,
                           JdbcTemplate jdbcTemplate) {
        this.userMapper = userMapper;
        this.toolCategoryMapper = toolCategoryMapper;
        this.systemSettingMapper = systemSettingMapper;
        this.passwordEncoder = passwordEncoder;
        this.dataSource = jdbcTemplate.getDataSource();
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        ensureSchemaCompatibility();
        createUserIfAbsent("admin", "123456", "Admin", UserType.ADMIN);
        createUserIfAbsent("user1", "123456", "User One", UserType.USER);
        toolCategoryMapper.ensureDefaultCategory();
        systemSettingMapper.ensureTable();
    }

    private void ensureSchemaCompatibility() {
        ensureColumn("ai_tools", "model_config_id", "ALTER TABLE ai_tools ADD COLUMN model_config_id BIGINT NULL");
        ensureColumn("agent_model_configs", "display_name", "ALTER TABLE agent_model_configs ADD COLUMN display_name VARCHAR(128) NULL");
        ensureColumn("agent_model_configs", "config_code", "ALTER TABLE agent_model_configs ADD COLUMN config_code VARCHAR(64) NULL");
        ensureColumn("agent_model_configs", "is_default", "ALTER TABLE agent_model_configs ADD COLUMN is_default TINYINT NOT NULL DEFAULT 0");
        ensureColumn("agent_model_configs", "is_deleted", "ALTER TABLE agent_model_configs ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0");
        ensureCommerceTables();
        ensureColumn("model_channel_nodes", "timeout_seconds", "ALTER TABLE model_channel_nodes ADD COLUMN timeout_seconds INT NOT NULL DEFAULT 90");
        ensureColumn("model_call_logs", "error_category", "ALTER TABLE model_call_logs ADD COLUMN error_category VARCHAR(64) NULL");
        ensureColumn("model_call_logs", "attempt_no", "ALTER TABLE model_call_logs ADD COLUMN attempt_no INT NULL");
        ensureColumn("model_call_logs", "fallback_from_node_id", "ALTER TABLE model_call_logs ADD COLUMN fallback_from_node_id BIGINT NULL");
        ensureColumn("model_call_logs", "response_metadata_json", "ALTER TABLE model_call_logs ADD COLUMN response_metadata_json TEXT NULL");
        ensureIndex("payment_transactions", "uk_payment_transactions_provider_event",
                "CREATE UNIQUE INDEX uk_payment_transactions_provider_event ON payment_transactions(provider, provider_trade_no, event_type)");
        ensureIndex("user_subscriptions", "uk_user_subscriptions_source_order",
                "CREATE UNIQUE INDEX uk_user_subscriptions_source_order ON user_subscriptions(source_order_id)");
    }

    private void ensureCommerceTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS subscription_plans (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  plan_code VARCHAR(64) NOT NULL UNIQUE,
                  plan_name VARCHAR(128) NOT NULL,
                  plan_type VARCHAR(32) NOT NULL,
                  duration_days INT NOT NULL DEFAULT 30,
                  price_cents INT NOT NULL DEFAULT 0,
                  credit_amount INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  description VARCHAR(512),
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS plan_entitlements (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  plan_id BIGINT NOT NULL,
                  pool_id BIGINT NULL,
                  entitlement_type VARCHAR(32) NOT NULL,
                  hourly_limit INT NULL,
                  daily_limit INT NULL,
                  max_concurrency INT NOT NULL DEFAULT 1,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_subscriptions (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  plan_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  starts_at DATETIME NOT NULL,
                  expires_at DATETIME NOT NULL,
                  source_order_id BIGINT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS payment_orders (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_no VARCHAR(64) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  product_type VARCHAR(32) NOT NULL,
                  product_id BIGINT NOT NULL,
                  channel VARCHAR(32) NOT NULL,
                  amount_cents INT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
                  provider_trade_no VARCHAR(128),
                  idempotency_key VARCHAR(128),
                  paid_at DATETIME NULL,
                  closed_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS payment_transactions (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id BIGINT NOT NULL,
                  provider VARCHAR(32) NOT NULL,
                  provider_trade_no VARCHAR(128),
                  event_type VARCHAR(64) NOT NULL,
                  raw_payload_json TEXT,
                  verified TINYINT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS payment_fulfillments (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id BIGINT NOT NULL UNIQUE,
                  product_type VARCHAR(32) NOT NULL,
                  product_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'FULFILLED',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_channel_pools (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  pool_code VARCHAR(64) NOT NULL UNIQUE,
                  pool_name VARCHAR(128) NOT NULL,
                  provider VARCHAR(64) NOT NULL,
                  model_name VARCHAR(128) NOT NULL,
                  spec_label VARCHAR(64) NOT NULL,
                  description VARCHAR(512),
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  sort_order INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_channel_nodes (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  pool_id BIGINT NOT NULL,
                  node_code VARCHAR(64) NOT NULL UNIQUE,
                  display_label VARCHAR(128),
                  provider_protocol VARCHAR(64) NOT NULL DEFAULT 'openai_compatible',
                  base_url VARCHAR(512),
                  api_key VARCHAR(1024),
                  model_name VARCHAR(128),
                  timeout_seconds INT NOT NULL DEFAULT 90,
                  max_concurrency INT NOT NULL DEFAULT 1,
                  current_concurrency INT NOT NULL DEFAULT 0,
                  hourly_limit INT NULL,
                  daily_limit INT NULL,
                  today_used INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
                  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                  last_error VARCHAR(512),
                  note VARCHAR(512),
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_chat_sessions (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  title VARCHAR(255) NOT NULL DEFAULT 'New chat',
                  pool_id BIGINT NULL,
                  node_id BIGINT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_chat_messages (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  session_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  role VARCHAR(32) NOT NULL,
                  content_text MEDIUMTEXT NOT NULL,
                  pool_id BIGINT NULL,
                  node_id BIGINT NULL,
                  token_count INT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_call_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  trace_id VARCHAR(128),
                  user_id BIGINT,
                  task_id BIGINT,
                  agent_run_id BIGINT,
                  chat_session_id BIGINT,
                  pool_id BIGINT,
                  node_id BIGINT,
                  model_config_id BIGINT,
                  provider VARCHAR(64),
                  model_name VARCHAR(128),
                  request_type VARCHAR(32) NOT NULL DEFAULT 'CHAT',
                  streaming TINYINT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL,
                  error_code VARCHAR(64),
                  error_category VARCHAR(64),
                  error_message TEXT,
                  latency_ms INT,
                  prompt_tokens INT,
                  completion_tokens INT,
                  total_tokens INT,
                  estimated_cost_cents INT,
                  attempt_no INT,
                  fallback_from_node_id BIGINT,
                  response_metadata_json TEXT,
                  started_at DATETIME,
                  finished_at DATETIME,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_access_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  subscription_id BIGINT NULL,
                  pool_id BIGINT NOT NULL,
                  node_id BIGINT NULL,
                  event_type VARCHAR(64) NOT NULL,
                  event_message VARCHAR(512),
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        seedCommerceData();
    }

    private void seedCommerceData() {
        jdbcTemplate.update("""
                INSERT INTO subscription_plans (plan_code, plan_name, plan_type, duration_days, price_cents, credit_amount, description)
                VALUES ('compute_starter_100', 'Starter Compute 100', 'COMPUTE_CREDIT', 0, 990, 100, 'Starter compute credits')
                ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name)
                """);
        jdbcTemplate.update("""
                INSERT INTO subscription_plans (plan_code, plan_name, plan_type, duration_days, price_cents, credit_amount, description)
                VALUES ('global_model_monthly', 'Global Model Monthly', 'MODEL_CHANNEL', 30, 16000, 0, 'Monthly access to global model pools')
                ON DUPLICATE KEY UPDATE plan_name = VALUES(plan_name)
                """);
        jdbcTemplate.update("""
                INSERT INTO model_channel_pools (pool_code, pool_name, provider, model_name, spec_label, description, sort_order)
                VALUES ('gemini_pro_pool', 'Gemini Pro Pool', 'gemini', 'gemini-pro', 'PRO', 'Shared Gemini Pro-compatible API channel pool', 10)
                ON DUPLICATE KEY UPDATE pool_name = VALUES(pool_name)
                """);
        jdbcTemplate.update("""
                INSERT INTO model_channel_pools (pool_code, pool_name, provider, model_name, spec_label, description, sort_order)
                VALUES ('gpt_pool', 'GPT Pool', 'openai', 'gpt-4.1', 'PRO', 'Shared GPT-compatible API channel pool', 20)
                ON DUPLICATE KEY UPDATE pool_name = VALUES(pool_name)
                """);
        jdbcTemplate.update("""
                INSERT INTO model_channel_nodes (pool_id, node_code, display_label, provider_protocol, model_name, max_concurrency, status, health_status, note)
                SELECT p.id, 'gemini-demo-01', 'Gemini Demo 01', 'openai_compatible', p.model_name, 2, 'AVAILABLE', 'HEALTHY', 'Demo node; replace API key in admin before production'
                FROM model_channel_pools p
                WHERE p.pool_code = 'gemini_pro_pool'
                  AND NOT EXISTS (SELECT 1 FROM model_channel_nodes n WHERE n.node_code = 'gemini-demo-01')
                """);
        jdbcTemplate.update("""
                INSERT INTO model_channel_nodes (pool_id, node_code, display_label, provider_protocol, model_name, max_concurrency, status, health_status, note)
                SELECT p.id, 'gpt-demo-01', 'GPT Demo 01', 'openai_compatible', p.model_name, 2, 'AVAILABLE', 'HEALTHY', 'Demo node; replace API key in admin before production'
                FROM model_channel_pools p
                WHERE p.pool_code = 'gpt_pool'
                  AND NOT EXISTS (SELECT 1 FROM model_channel_nodes n WHERE n.node_code = 'gpt-demo-01')
                """);
        jdbcTemplate.update("""
                INSERT INTO plan_entitlements (plan_id, pool_id, entitlement_type, hourly_limit, daily_limit, max_concurrency)
                SELECT p.id, NULL, 'MODEL_POOL', 60, 300, 1
                FROM subscription_plans p
                WHERE p.plan_code = 'global_model_monthly'
                  AND NOT EXISTS (SELECT 1 FROM plan_entitlements e WHERE e.plan_id = p.id AND e.pool_id IS NULL)
                """);
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

    private void ensureIndex(String tableName, String indexName, String ddl) {
        try (Connection connection = dataSource.getConnection()) {
            if (!indexExists(connection, tableName, indexName)) {
                connection.createStatement().execute(ddl);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to ensure database index " + tableName + "." + indexName, exception);
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        String[] tableCandidates = {tableName, tableName.toUpperCase()};
        String[] indexCandidates = {indexName, indexName.toUpperCase()};
        for (String table : tableCandidates) {
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
                while (indexes.next()) {
                    String current = indexes.getString("INDEX_NAME");
                    for (String index : indexCandidates) {
                        if (index.equals(current)) {
                            return true;
                        }
                    }
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

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS credit_recharge_packages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_code VARCHAR(64) NOT NULL UNIQUE,
  package_name VARCHAR(128) NOT NULL,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
  validity_days INT NOT NULL DEFAULT 0,
  benefits_json JSON NULL,
  recommended TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_recharge_packages_status_sort(status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_recharge_orders (
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
  pay_url TEXT,
  qr_code_url VARCHAR(512),
  external_trade_no VARCHAR(128),
  idempotency_key VARCHAR(128),
  paid_at DATETIME,
  credited_at DATETIME,
  closed_at DATETIME,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_recharge_user_idem(user_id, idempotency_key),
  UNIQUE KEY uk_recharge_external_trade_no(external_trade_no),
  KEY idx_recharge_orders_user_created(user_id, created_at),
  KEY idx_recharge_orders_status_expires(status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO credit_recharge_packages(package_code, package_name, credits, price_amount, currency, validity_days,
                                     benefits_json, recommended, sort_order, status)
VALUES
  -- 月度套餐（markup 1.50，利润率 32-33%）
  ('monthly_starter', '入门版·月卡', 4000, 59.00, 'CNY', 30, JSON_ARRAY('优先排队', '每日登录送20算力', '灵活月付'), 0, 10, 'ACTIVE'),
  ('monthly_growth', '成长版·月卡', 10000, 149.00, 'CNY', 30, JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '灵活月付'), 1, 20, 'ACTIVE'),
  ('monthly_pro', '专业版·月卡', 20000, 299.00, 'CNY', 30, JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '灵活月付'), 0, 30, 'ACTIVE'),
  ('monthly_flagship', '旗舰版·月卡', 40000, 599.00, 'CNY', 30, JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '灵活月付'), 0, 40, 'ACTIVE'),

  -- 季度套餐（季卡 9 折：价格 = 月卡×2.7，算力 = 月卡×3；markup 1.50 下毛利率约 50%）
  ('quarterly_starter', '入门版·季卡', 12000, 159.00, 'CNY', 90, JSON_ARRAY('优先排队', '每日登录送20算力', '季卡9折优惠'), 0, 50, 'ACTIVE'),
  ('quarterly_growth', '成长版·季卡', 30000, 399.00, 'CNY', 90, JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '季卡9折优惠'), 1, 60, 'ACTIVE'),
  ('quarterly_pro', '专业版·季卡', 60000, 799.00, 'CNY', 90, JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '季卡9折优惠'), 0, 70, 'ACTIVE'),
  ('quarterly_flagship', '旗舰版·季卡', 120000, 1599.00, 'CNY', 90, JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '季卡9折优惠'), 0, 80, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  package_name = VALUES(package_name),
  credits = VALUES(credits),
  price_amount = VALUES(price_amount),
  currency = VALUES(currency),
  validity_days = VALUES(validity_days),
  benefits_json = VALUES(benefits_json),
  recommended = VALUES(recommended),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

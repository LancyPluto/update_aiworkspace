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
  ('starter_1000', 'Starter credits', 1000, 10.00, 'CNY', 30, JSON_ARRAY('Priority queue'), 0, 10, 'ACTIVE'),
  ('growth_5000', 'Growth credits', 5000, 45.00, 'CNY', 90, JSON_ARRAY('Priority queue', 'API acceleration'), 1, 20, 'ACTIVE'),
  ('pro_12000', 'Pro credits', 12000, 99.00, 'CNY', 180, JSON_ARRAY('Priority queue', 'API acceleration', 'Model consulting'), 0, 30, 'ACTIVE')
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

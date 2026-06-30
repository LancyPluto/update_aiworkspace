-- Gift card packages (denominations available for purchase)
CREATE TABLE IF NOT EXISTS gift_card_packages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_code VARCHAR(64) NOT NULL UNIQUE,
  package_name VARCHAR(128) NOT NULL,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  card_theme VARCHAR(32) NOT NULL DEFAULT 'classic',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Gift card instances held by users
CREATE TABLE IF NOT EXISTS gift_cards (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  card_code VARCHAR(64) NOT NULL UNIQUE,
  package_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  original_user_id BIGINT NOT NULL,
  credits INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UNUSED',
  recharge_order_id BIGINT,
  redeemed_at DATETIME NULL,
  gifted_from_user_id BIGINT NULL,
  gifted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_gift_cards_owner (owner_user_id, status),
  INDEX idx_gift_cards_code (card_code)
);

-- Extend credit_recharge_orders to support gift card purchases
ALTER TABLE credit_recharge_orders ADD COLUMN order_type VARCHAR(32) NOT NULL DEFAULT 'CREDITS';
ALTER TABLE credit_recharge_orders ADD COLUMN gift_card_package_id BIGINT NULL;

-- Seed gift card denominations
INSERT INTO gift_card_packages (package_code, package_name, credits, price_amount, card_theme, sort_order) VALUES
('gift_200', '200算力礼品卡', 200, 19.00, 'blue', 1),
('gift_500', '500算力礼品卡', 500, 45.00, 'purple', 2),
('gift_1000', '1000算力礼品卡', 1000, 88.00, 'gold', 3),
('gift_3000', '3000算力礼品卡', 3000, 249.00, 'dark', 4);

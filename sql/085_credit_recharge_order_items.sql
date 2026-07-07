SET NAMES utf8mb4;

-- Support checkout of multiple gift card denominations in one recharge order.
CREATE TABLE IF NOT EXISTS credit_recharge_order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  gift_card_package_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  credits INT NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL,
  item_type VARCHAR(32) NOT NULL DEFAULT 'GIFT_CARD',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_recharge_order_items_order (order_id)
);

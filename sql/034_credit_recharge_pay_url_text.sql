SET NAMES utf8mb4;

ALTER TABLE credit_recharge_orders
  MODIFY COLUMN pay_url TEXT NULL;

-- Custom recharge orders do not bind a package; package_id must be nullable.
SET NAMES utf8mb4;

ALTER TABLE credit_recharge_orders
  MODIFY COLUMN package_id BIGINT NULL COMMENT 'NULL for custom amount recharge';

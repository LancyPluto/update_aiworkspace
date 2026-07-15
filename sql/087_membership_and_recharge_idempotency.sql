SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS assert_no_paid_membership_orders;
DELIMITER $$

CREATE PROCEDURE assert_no_paid_membership_orders()
BEGIN
  DECLARE paid_membership_orders BIGINT DEFAULT 0;

  SELECT COUNT(*) INTO paid_membership_orders
  FROM credit_recharge_orders
  WHERE order_type = 'MEMBERSHIP'
    AND status IN ('PAID', 'CREDITED');

  IF paid_membership_orders > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Membership balance migration aborted: paid membership orders exist';
  END IF;
END $$

DELIMITER ;

CALL assert_no_paid_membership_orders();
DROP PROCEDURE IF EXISTS assert_no_paid_membership_orders;

ALTER TABLE credit_accounts
  ADD COLUMN permanent_balance INT NOT NULL DEFAULT 0 AFTER balance,
  ADD COLUMN permanent_frozen INT NOT NULL DEFAULT 0 AFTER frozen,
  ADD COLUMN membership_frozen INT NOT NULL DEFAULT 0 AFTER permanent_frozen,
  ADD COLUMN gift_frozen INT NOT NULL DEFAULT 0 AFTER membership_frozen,
  ADD COLUMN expired_membership_frozen INT NOT NULL DEFAULT 0 AFTER gift_frozen,
  ADD COLUMN total_expired INT NOT NULL DEFAULT 0 AFTER total_consumed,
  ADD COLUMN bucket_schema_version INT NOT NULL DEFAULT 1 AFTER total_expired;

UPDATE credit_accounts
SET permanent_balance = CASE
        WHEN membership_balance = 0 AND gift_balance = 0 THEN balance
        ELSE membership_balance
    END,
    permanent_frozen = frozen,
    membership_balance = 0,
    membership_frozen = 0,
    bucket_schema_version = 2
WHERE bucket_schema_version < 2;

ALTER TABLE credit_recharge_orders
  ADD COLUMN request_fingerprint VARCHAR(64) NULL AFTER idempotency_key,
  ADD COLUMN package_code_snapshot VARCHAR(64) NULL AFTER gift_card_package_id,
  ADD COLUMN validity_days_snapshot INT NULL AFTER package_code_snapshot;

CREATE TABLE IF NOT EXISTS user_memberships (
  user_id BIGINT PRIMARY KEY,
  status VARCHAR(16) NOT NULL DEFAULT 'NONE',
  package_id BIGINT NULL,
  package_code VARCHAR(64) NULL,
  order_id BIGINT NULL,
  started_at DATETIME NULL,
  expires_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_membership_order (order_id),
  KEY idx_user_membership_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE gift_cards
  ADD COLUMN issuance_key VARCHAR(128) NULL AFTER recharge_order_id;

CREATE UNIQUE INDEX uk_gift_cards_issuance_key ON gift_cards(issuance_key);

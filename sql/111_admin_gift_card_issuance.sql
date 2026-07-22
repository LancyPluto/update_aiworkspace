SET NAMES utf8mb4;

ALTER TABLE gift_cards
  ADD COLUMN issuance_operator_id BIGINT NULL AFTER issuance_key,
  ADD COLUMN issuance_reason VARCHAR(512) NULL AFTER issuance_operator_id;

UPDATE gift_card_packages
SET credits = 0,
    status = 'HIDDEN',
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'admin_default';

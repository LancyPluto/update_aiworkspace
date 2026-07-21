SET NAMES utf8mb4;

-- MySQL commits DDL statement by statement. Guard every column so a partially
-- applied migration can be retried safely by the deployment runner.
DROP PROCEDURE IF EXISTS ensure_membership_gift_card_columns;

DELIMITER $$
CREATE PROCEDURE ensure_membership_gift_card_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_card_packages'
      AND column_name = 'card_type'
  ) THEN
    ALTER TABLE gift_card_packages
      ADD COLUMN card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER card_theme;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_card_packages'
      AND column_name = 'required_member_tier'
  ) THEN
    ALTER TABLE gift_card_packages
      ADD COLUMN required_member_tier VARCHAR(32) NULL AFTER card_type;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_cards'
      AND column_name = 'card_type'
  ) THEN
    ALTER TABLE gift_cards
      ADD COLUMN card_type VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER credits;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_cards'
      AND column_name = 'required_member_tier'
  ) THEN
    ALTER TABLE gift_cards
      ADD COLUMN required_member_tier VARCHAR(32) NULL AFTER card_type;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'credit_recharge_order_items'
      AND column_name = 'card_type_snapshot'
  ) THEN
    ALTER TABLE credit_recharge_order_items
      ADD COLUMN card_type_snapshot VARCHAR(32) NOT NULL DEFAULT 'CREDIT' AFTER item_type;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'credit_recharge_order_items'
      AND column_name = 'required_member_tier_snapshot'
  ) THEN
    ALTER TABLE credit_recharge_order_items
      ADD COLUMN required_member_tier_snapshot VARCHAR(32) NULL AFTER card_type_snapshot;
  END IF;
END $$
DELIMITER ;

CALL ensure_membership_gift_card_columns();
DROP PROCEDURE IF EXISTS ensure_membership_gift_card_columns;

INSERT INTO credit_recharge_packages (
  package_code,
  package_name,
  credits,
  price_amount,
  currency,
  validity_days,
  benefits_json,
  recommended,
  sort_order,
  status
) VALUES
  ('monthly_starter', '标准版·月卡', 4000, 59.00, 'CNY', 30,
    JSON_ARRAY('优先排队', '每日登录送20算力', '灵活月付'), 0, 10, 'ACTIVE'),
  ('monthly_growth', '进阶版·月卡', 10500, 149.00, 'CNY', 30,
    JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '灵活月付'), 1, 20, 'ACTIVE'),
  ('monthly_pro', '高级版·月卡', 22000, 299.00, 'CNY', 30,
    JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '灵活月付'), 0, 30, 'ACTIVE'),
  ('monthly_flagship', '豪华版·月卡', 45000, 599.00, 'CNY', 30,
    JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '灵活月付'), 0, 40, 'ACTIVE'),
  ('quarterly_starter', '标准版·季卡', 12000, 169.00, 'CNY', 90,
    JSON_ARRAY('优先排队', '每日登录送20算力', '季卡约9.5折'), 0, 50, 'ACTIVE'),
  ('quarterly_growth', '进阶版·季卡', 31500, 425.00, 'CNY', 90,
    JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '季卡约9.5折'), 1, 60, 'ACTIVE'),
  ('quarterly_pro', '高级版·季卡', 66000, 849.00, 'CNY', 90,
    JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '季卡约9.5折'), 0, 70, 'ACTIVE'),
  ('quarterly_flagship', '豪华版·季卡', 135000, 1699.00, 'CNY', 90,
    JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '季卡约9.5折'), 0, 80, 'ACTIVE'),
  ('yearly_starter', '标准版·年卡', 48000, 639.00, 'CNY', 365,
    JSON_ARRAY('优先排队', '每日登录送20算力', '年付立省10%'), 0, 90, 'ACTIVE'),
  ('yearly_growth', '进阶版·年卡', 126000, 1609.00, 'CNY', 365,
    JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '年付立省10%'), 1, 100, 'ACTIVE'),
  ('yearly_pro', '高级版·年卡', 264000, 3229.00, 'CNY', 365,
    JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '年付立省10%'), 0, 110, 'ACTIVE'),
  ('yearly_flagship', '豪华版·年卡', 540000, 6469.00, 'CNY', 365,
    JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '年付立省10%'), 0, 120, 'ACTIVE')
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

INSERT INTO gift_card_packages (
  package_code,
  package_name,
  credits,
  price_amount,
  currency,
  card_theme,
  card_type,
  required_member_tier,
  status,
  sort_order
) VALUES
  ('member_gift_starter', '标准版会员礼品卡', 4000, 69.00, 'CNY', 'blue', 'MEMBER_CREDIT', 'starter', 'ACTIVE', 101),
  ('member_gift_growth', '进阶版会员礼品卡', 10500, 169.00, 'CNY', 'purple', 'MEMBER_CREDIT', 'growth', 'ACTIVE', 102),
  ('member_gift_pro', '高级版会员礼品卡', 22000, 339.00, 'CNY', 'gold', 'MEMBER_CREDIT', 'pro', 'ACTIVE', 103),
  ('member_gift_flagship', '豪华版会员礼品卡', 45000, 679.00, 'CNY', 'dark', 'MEMBER_CREDIT', 'flagship', 'ACTIVE', 104)
ON DUPLICATE KEY UPDATE
  package_name = VALUES(package_name),
  credits = VALUES(credits),
  price_amount = VALUES(price_amount),
  currency = VALUES(currency),
  card_theme = VALUES(card_theme),
  card_type = VALUES(card_type),
  required_member_tier = VALUES(required_member_tier),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

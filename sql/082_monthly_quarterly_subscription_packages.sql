SET NAMES utf8mb4;

-- 补齐包月 / 包季订阅套餐（生产环境若仅有 legacy 与 yearly_* 则前端切换月/季时无卡片）
INSERT INTO credit_recharge_packages(package_code, package_name, credits, price_amount, currency, validity_days,
                                     benefits_json, recommended, sort_order, status)
VALUES
  ('monthly_starter', '入门版·月卡', 4000, 59.00, 'CNY', 30, JSON_ARRAY('优先排队', '每日登录送20算力', '灵活月付'), 0, 10, 'ACTIVE'),
  ('monthly_growth', '成长版·月卡', 10000, 149.00, 'CNY', 30, JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '灵活月付'), 1, 20, 'ACTIVE'),
  ('monthly_pro', '专业版·月卡', 20000, 299.00, 'CNY', 30, JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '灵活月付'), 0, 30, 'ACTIVE'),
  ('monthly_flagship', '旗舰版·月卡', 40000, 599.00, 'CNY', 30, JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '灵活月付'), 0, 40, 'ACTIVE'),
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

-- 下线旧版一次性充值档位，避免与订阅套餐混用
UPDATE credit_recharge_packages
SET status = 'INACTIVE'
WHERE package_code IN ('starter_1000', 'growth_5000', 'pro_12000', 'test_1000');

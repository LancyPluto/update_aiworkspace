SET NAMES utf8mb4;

-- 年度套餐：相对月卡原价 12 个月享 37 折（与充值方案文档「年度引入」方向一致）
INSERT INTO credit_recharge_packages(package_code, package_name, credits, price_amount, currency, validity_days,
                                     benefits_json, recommended, sort_order, status)
VALUES
  ('yearly_starter', '入门版·年卡', 52000, 446.00, 'CNY', 365, JSON_ARRAY('优先排队', '每日登录送20算力', '限时37折优惠'), 0, 90, 'ACTIVE'),
  ('yearly_growth', '成长版·年卡', 130000, 1128.00, 'CNY', 365, JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '限时37折优惠'), 1, 100, 'ACTIVE'),
  ('yearly_pro', '专业版·年卡', 260000, 2264.00, 'CNY', 365, JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '限时37折优惠'), 0, 110, 'ACTIVE'),
  ('yearly_flagship', '旗舰版·年卡', 520000, 4528.00, 'CNY', 365, JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '限时37折优惠'), 0, 120, 'ACTIVE')
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

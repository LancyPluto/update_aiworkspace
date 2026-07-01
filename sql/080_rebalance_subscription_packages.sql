SET NAMES utf8mb4;

-- 在平台加价 markup = 1.50 下校准包季套餐：
-- 价格 = 月卡 × 3 × 0.9（季卡 9 折），算力 = 月卡 × 3（不额外送点，避免毛利被稀释）
-- 毛利率公式：(price - credits / 150) / price，四档季卡均 > 45%

UPDATE credit_recharge_packages
SET credits = 12000,
    price_amount = 159.00,
    benefits_json = JSON_ARRAY('优先排队', '每日登录送20算力', '季卡9折优惠'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'quarterly_starter';

UPDATE credit_recharge_packages
SET credits = 30000,
    price_amount = 399.00,
    benefits_json = JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '季卡9折优惠'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'quarterly_growth';

UPDATE credit_recharge_packages
SET credits = 60000,
    price_amount = 799.00,
    benefits_json = JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '季卡9折优惠'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'quarterly_pro';

UPDATE credit_recharge_packages
SET credits = 120000,
    price_amount = 1599.00,
    benefits_json = JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '季卡9折优惠'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'quarterly_flagship';

-- 月卡保持 markup 1.50 下约 50%+ 毛利率（面值占比约 68%，实际成本约 45%）
UPDATE credit_recharge_packages
SET benefits_json = JSON_ARRAY('优先排队', '每日登录送20算力', '灵活月付'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'monthly_starter';

UPDATE credit_recharge_packages
SET benefits_json = JSON_ARRAY('优先排队', 'API 加速', '每日登录送30算力', '灵活月付'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'monthly_growth';

UPDATE credit_recharge_packages
SET benefits_json = JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务', '每日登录送50算力', '灵活月付'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'monthly_pro';

UPDATE credit_recharge_packages
SET benefits_json = JSON_ARRAY('无限并发', '专属客服', '定制模型支持', '每日登录送100算力', '灵活月付'),
    updated_at = CURRENT_TIMESTAMP
WHERE package_code = 'monthly_flagship';

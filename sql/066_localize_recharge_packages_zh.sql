SET NAMES utf8mb4;

UPDATE credit_recharge_packages
SET package_name = '入门套餐',
    benefits_json = JSON_ARRAY('优先排队'),
    updated_at = NOW()
WHERE package_code = 'starter_1000';

UPDATE credit_recharge_packages
SET package_name = '成长套餐',
    benefits_json = JSON_ARRAY('优先排队', 'API 加速'),
    updated_at = NOW()
WHERE package_code = 'growth_5000';

UPDATE credit_recharge_packages
SET package_name = '专业套餐',
    benefits_json = JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务'),
    updated_at = NOW()
WHERE package_code = 'pro_12000';

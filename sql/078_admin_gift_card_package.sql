-- 管理员手动加算力专用的默认礼品卡套餐
-- 用于后台管理员给指定用户添加算力时创建礼品卡记录

INSERT INTO gift_card_packages (package_code, package_name, credits, price_amount, currency, card_theme, status, sort_order) VALUES
('admin_default', '管理员赠送礼品卡', 0, 0.00, 'CNY', 'green', 'ACTIVE', 999)
ON DUPLICATE KEY UPDATE
    package_name = '管理员赠送礼品卡',
    credits = 0,
    price_amount = 0.00,
    currency = 'CNY',
    card_theme = 'green',
    status = 'ACTIVE',
    sort_order = 999;

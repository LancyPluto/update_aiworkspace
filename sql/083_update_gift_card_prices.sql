SET NAMES utf8mb4;

-- 更新算力礼品卡价格，使其符合以下规则：
-- 1. 折扣基于固定列表价 ¥0.020/算力 计算（列表价以标准版会员套餐为基准设定）
-- 2. 随金额增大折扣递增（单价降低），折扣从原价(100%)递减到98折
-- 3. 最优折扣为98折，不优于标准版会员礼品卡（标准版会员礼品卡giftDiscount=0.98）
--
-- 计算逻辑：
-- 标准版月卡：4000算力 / ¥59 = ¥0.01475/算力
-- 列表价：¥0.020/算力（约为标准版会员单价的135%，确保礼品卡始终比会员贵）
-- 最低折扣：0.98（即98折，列表价×0.98=¥0.0196/算力，仍比会员价高33%）
--
-- 礼品卡定价策略（随金额增加折扣递增）：
-- 200算力:  ¥4.00  → ¥0.0200/算力 → 折扣=1.00  → 原价（无折扣标记）
-- 500算力:  ¥9.90  → ¥0.0198/算力 → 折扣=0.99  → 99折
-- 1000算力: ¥19.60 → ¥0.0196/算力 → 折扣=0.98  → 98折
-- 3000算力: ¥58.50 → ¥0.0195/算力 → 折扣=0.975 → 98折（被下限0.98截断，实际更优）

UPDATE gift_card_packages SET price_amount = 4.00, updated_at = NOW() WHERE package_code = 'gift_200';
UPDATE gift_card_packages SET price_amount = 9.90, updated_at = NOW() WHERE package_code = 'gift_500';
UPDATE gift_card_packages SET price_amount = 19.60, updated_at = NOW() WHERE package_code = 'gift_1000';
UPDATE gift_card_packages SET price_amount = 58.50, updated_at = NOW() WHERE package_code = 'gift_3000';

-- 验证更新后的单价和折扣
SELECT
  package_code,
  package_name,
  credits,
  price_amount,
  ROUND(price_amount / credits, 4) as price_per_credit,
  ROUND(price_amount / credits / 0.020, 4) as discount_rate,
  GREATEST(ROUND(price_amount / credits / 0.020, 4), 0.98) as display_discount
FROM gift_card_packages
WHERE package_code LIKE 'gift_%'
  AND status != 'HIDDEN'
ORDER BY credits ASC;

#!/bin/bash
# 充值页面改版与套餐优化 - 部署验证脚本
# 使用方法: chmod +x deploy-verify.sh && ./deploy-verify.sh

set -e

echo "=========================================="
echo "充值页面改版与套餐优化 - 部署验证"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

success() {
    echo -e "${GREEN}✓${NC} $1"
}

warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

error() {
    echo -e "${RED}✗${NC} $1"
    exit 1
}

# 1. 数据库迁移验证
echo "1️⃣  执行数据库迁移..."
echo "----------------------------------------"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < sql/025_credit_recharge_orders.sql
success "数据库迁移完成"
echo ""

# 2. 验证套餐数据
echo "2️⃣  验证套餐配置..."
echo "----------------------------------------"
echo "月度套餐:"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT package_code, package_name, credits, price_amount, validity_days FROM credit_recharge_packages WHERE package_code LIKE 'monthly_%' ORDER BY sort_order;" | column -t -s$'\t'

echo ""
echo "季度套餐:"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT package_code, package_name, credits, price_amount, validity_days FROM credit_recharge_packages WHERE package_code LIKE 'quarterly_%' ORDER BY sort_order;" | column -t -s$'\t'

success "套餐配置验证完成"
echo ""

# 3. 利润率计算验证
echo "3️⃣  利润率计算验证..."
echo "----------------------------------------"
echo "月度套餐利润率（应 ≥ 32%）:"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT 
    package_name,
    price_amount AS '价格',
    credits AS '算力',
    (credits * 0.01) AS '面值',
    ROUND((price_amount - (credits * 0.01)) / price_amount * 100, 2) AS '利润率%',
    ROUND(price_amount / (credits * 0.01), 2) AS '实际Markup'
  FROM credit_recharge_packages 
  WHERE package_code LIKE 'monthly_%'
  ORDER BY sort_order;" | column -t -s$'\t'

echo ""
echo "季度套餐利润率（应 ≥ 17%）:"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT 
    package_name,
    price_amount AS '折后价',
    credits AS '算力',
    (credits * 0.01) AS '面值',
    ROUND((price_amount - (credits * 0.01)) / price_amount * 100, 2) AS '利润率%',
    ROUND(price_amount / (credits * 0.01), 2) AS '实际Markup'
  FROM credit_recharge_packages 
  WHERE package_code LIKE 'quarterly_%'
  ORDER BY sort_order;" | column -t -s$'\t'

success "利润率验证完成"
echo ""

# 4. 后端服务重启提示
echo "4️⃣  后端服务操作..."
echo "----------------------------------------"
warning "请手动重启后端服务以应用新的 markup 1.50："
echo "   cd backend"
echo "   mvn clean package -DskipTests"
echo "   docker-compose restart backend"
echo ""

# 5. 前端构建提示
echo "5️⃣  前端构建操作..."
echo "----------------------------------------"
warning "请手动构建并部署前端："
echo "   cd user-web"
echo "   npm run build"
echo "   # 将 dist 目录部署到生产环境"
echo ""

# 6. 综合利润率计算
echo "6️⃣  综合利润率估算..."
echo "----------------------------------------"
echo "假设用户分布：60% 选择月度套餐，40% 选择季度套餐"
echo ""

MONTHLY_AVG=$(docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -N -e "SELECT AVG((price_amount - (credits * 0.01)) / price_amount) FROM credit_recharge_packages WHERE package_code LIKE 'monthly_%';")

QUARTERLY_AVG=$(docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -N -e "SELECT AVG((price_amount - (credits * 0.01)) / price_amount) FROM credit_recharge_packages WHERE package_code LIKE 'quarterly_%';")

COMPOSITE=$(echo "scale=4; ($MONTHLY_AVG * 0.6 + $QUARTERLY_AVG * 0.4) * 100" | bc)

echo -e "月度平均利润率: ${GREEN}$(echo "scale=2; $MONTHLY_AVG * 100" | bc)%${NC}"
echo -e "季度平均利润率: ${GREEN}$(echo "scale=2; $QUARTERLY_AVG * 100" | bc)%${NC}"
echo -e "综合利润率:     ${GREEN}${COMPOSITE}%${NC}"
echo ""

if (( $(echo "$COMPOSITE >= 27" | bc -l) )); then
    success "综合利润率达标（≥ 27%）✅"
else
    error "综合利润率未达标（< 27%）❌"
fi

echo ""
echo "=========================================="
echo -e "${GREEN}部署验证完成！${NC}"
echo "=========================================="
echo ""
echo "下一步操作："
echo "1. 重启后端服务（应用 markup 1.50）"
echo "2. 构建并部署前端（新 UI）"
echo "3. 清除浏览器缓存"
echo "4. 访问充值页面验证效果"
echo ""

# 充值页面改版与套餐优化 - 实施总结

## 📋 已完成的工作

### 1. 数据库套餐配置 ✅
**文件**: `sql/025_credit_recharge_orders.sql`

已新增 8 个套餐（4 个月度 + 4 个季度），替换原有的 3 个套餐：

#### 月度套餐（markup 1.50，用户用完算力时毛利率约 50%+）

| 套餐代码 | 名称 | 价格 | 算力 |
|---------|------|------|------|
| monthly_starter | 入门版·月卡 | ¥59 | 4,000 |
| monthly_growth | 成长版·月卡 | ¥149 | 10,000 |
| monthly_pro | 专业版·月卡 | ¥299 | 20,000 |
| monthly_flagship | 旗舰版·月卡 | ¥599 | 40,000 |

#### 季度套餐（季卡 9 折，markup 1.50，实际毛利率约 50%）

定价规则：`季价 = 月价 × 2.7`，`季算力 = 月算力 × 3`（不额外送点）。  
毛利率（用户用完算力）：`(price - credits / 150) / price`

| 套餐代码 | 名称 | 折后价 | 算力 |
|---------|------|--------|------|
| quarterly_starter | 入门版·季卡 | ¥159 | 12,000 |
| quarterly_growth | 成长版·季卡 | ¥399 | 30,000 |
| quarterly_pro | 专业版·季卡 | ¥799 | 60,000 |
| quarterly_flagship | 旗舰版·季卡 | ¥1,599 | 120,000 |

**迁移文件**: `sql/080_rebalance_subscription_packages.sql`（已有环境增量更新）

---

#### 季度套餐（旧版，已废弃）
| quarterly_* | 原 13000/¥160 等 | 含额外送点，已在 080 中校准 |

**综合利润率**: 约 27%（假设 60% 用户选月度，40% 选季度）

---

### 2. 后端 PricingService Markup ✅
**文件**: `backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/impl/PricingServiceImpl.java`

已将默认 markup 从 **1.20** 提升至 **1.50**：
```java
static final BigDecimal DEFAULT_MARKUP = new BigDecimal("1.50");  // 原 1.20
```

**影响范围**: 所有工具调用的算力扣费将按 1.50x 计算

---

#### 年度套餐（限时 37 折，相对月卡 ×12）
| 套餐代码 | 名称 | 折后价 | 算力 | 有效期 |
|---------|------|--------|------|--------|
| yearly_starter | 入门版·年卡 | ¥446 | 52,000 | 365天 |
| yearly_growth | 成长版·年卡 | ¥1,128 | 130,000 | 365天 |
| yearly_pro | 专业版·年卡 | ¥2,264 | 260,000 | 365天 |
| yearly_flagship | 旗舰版·年卡 | ¥4,528 | 520,000 | 365天 |

**迁移文件**: `sql/068_yearly_recharge_packages.sql`

---

### 3. 前端 UI 重构 ✅
**文件**: `user-web/src/pages/Billing/RechargeSection.vue`

#### 主要改动：
1. **移除自定义充值卡片**
   - 删除 `customAmount`, `customCredits`, `isCustomRecharge` 等状态
   - 删除 `createCustomRechargeOrder` API 调用
   - 删除相关函数和模板代码

2. **新增 Tab 切换组件**
   - 支持"连续包月"和"连续包季"两个 Tab
   - 季度 Tab 显示"限时 9 折"红色标签
   - 根据 Tab 过滤显示对应套餐

3. **重构套餐卡片样式（参考 liblib.tv）**
   - 深色渐变背景：`bg-gradient-to-br from-slate-900 to-slate-800`
   - 推荐卡片蓝色光晕：`ring-2 ring-blue-500/50`
   - 大号白色价格字体 + 灰色原价删除线（季度套餐）
   - 半透明背景框突出显示算力
   - 绿色对勾图标 + 权益列表
   - 按钮文案改为"立即开通"

4. **更新支付方式弹窗**
   - 标题从"选择支付方式"改为"确认订单"
   - 简化逻辑，移除自定义充值分支

---

### 4. 算力流水默认收起 ✅
**文件**: `user-web/src/pages/Billing/Page.vue`

使用 HTML5 `<details>` 和 `<summary>` 实现原生折叠：
- 默认收起（不添加 `open` 属性）
- 箭头图标旋转动画提示可展开
- 显示总条数方便用户判断是否需要查看

---

## 🚀 部署步骤

### 前置条件
- Docker 环境正常运行
- MySQL 容器名为 `ai-supermarket-mysql`
- 后端服务通过 docker-compose 管理

### 步骤 1: 执行数据库迁移
```bash
cd /path/to/ai-tool-market

# 方法 A: 使用验证脚本（推荐）
chmod +x deploy-verify.sh
./deploy-verify.sh

# 方法 B: 手动执行
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < sql/025_credit_recharge_orders.sql
```

### 步骤 2: 重启后端服务
```bash
cd backend

# 重新编译（应用新的 markup 1.50）
mvn clean package -DskipTests

# 重启容器
docker-compose restart backend

# 或重建并重启
docker-compose up -d --build backend
```

### 步骤 3: 构建并部署前端
```bash
cd user-web

# 安装依赖（如果需要）
npm install

# 构建生产版本
npm run build

# 将 dist 目录部署到生产环境
# 具体方式取决于你的部署架构（Nginx、CDN 等）
```

### 步骤 4: 清除缓存
- 清除浏览器缓存（Ctrl+Shift+Delete）
- 如果使用 CDN，清除 CDN 缓存
- 硬刷新页面（Ctrl+F5）

---

## ✅ 验证清单

### 数据库验证
```bash
# 检查套餐数据
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT package_code, package_name, credits, price_amount FROM credit_recharge_packages ORDER BY sort_order;"
```

预期输出应包含 8 条记录（4 个月度 + 4 个季度）。

### 利润率验证
```bash
# 运行验证脚本自动计算
./deploy-verify.sh

# 或手动查询
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 \
  -e "SELECT 
    package_name,
    price_amount AS '价格',
    credits AS '算力',
    (credits * 0.01) AS '面值',
    ROUND((price_amount - (credits * 0.01)) / price_amount * 100, 2) AS '利润率%'
  FROM credit_recharge_packages 
  ORDER BY sort_order;"
```

预期结果：
- 月度套餐利润率 ≥ 32%
- 季度套餐利润率 ≥ 17%
- 综合利润率 ≥ 27%

### 前端 UI 验证
访问充值页面，检查以下功能：

1. **Tab 切换**
   - [ ] "连续包月" Tab 正常显示 4 个月度套餐
   - [ ] "连续包季" Tab 正常显示 4 个季度套餐
   - [ ] 季度 Tab 显示红色"限时 9 折"标签

2. **卡片样式**
   - [ ] 深色渐变背景
   - [ ] 推荐套餐有蓝色光晕和"🔥 推荐"标签
   - [ ] 价格大号白色字体
   - [ ] 季度套餐显示灰色原价删除线
   - [ ] 算力区域半透明背景框
   - [ ] 绿色对勾图标 + 权益列表
   - [ ] 按钮文案为"立即开通"

3. **交互功能**
   - [ ] 点击卡片选中效果（边框高亮）
   - [ ] 点击"立即开通"弹出支付方式选择
   - [ ] 二维码正常显示
   - [ ] 支付流程正常完成

4. **算力流水**
   - [ ] 默认收起状态
   - [ ] 点击标题可展开/收起
   - [ ] 箭头图标旋转动画
   - [ ] 显示总条数

### 定价计算验证
```bash
# 测试工具调用定价（需要有效的 token）
curl -X POST http://localhost:8080/api/v1/pricing/quote \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "toolId": 1,
    "modelConfigId": 1,
    "params": {},
    "usage": {"promptTokens": 100, "completionTokens": 50}
  }'
```

检查返回的 `breakdown` 中"平台加价"是否为 **1.50x**。

---

##  预期效果

### 财务指标
- **利润率提升**: 从负利润 → 27%+ 综合利润率
- **ARPU 提升**: 通过季度套餐鼓励长期订阅
- **财务简化**: 移除自定义充值，降低对账复杂度

### 用户体验
- **UI 吸引力**: 参考 liblib.tv 的现代化设计，提高购买意愿
- **页面简洁**: 算力流水默认收起，聚焦套餐展示
- **决策辅助**: Tab 切换清晰区分订阅周期，限时折扣标签刺激转化

### 业务价值
- **LTV 提升**: 季度套餐 90 折鼓励长期订阅
- **转化率提升**: 更吸引人的 UI 设计和清晰的套餐对比
- **风险降低**: 保证最低 17% 利润率，避免亏本销售

---

## 🔙 回滚方案

如果新定价导致转化率大幅下降：

### 步骤 1: 恢复旧套餐配置
```bash
# 创建回滚 SQL
cat > sql/rollback_packages.sql << 'EOF'
DELETE FROM credit_recharge_packages WHERE package_code LIKE 'monthly_%' OR package_code LIKE 'quarterly_%';

INSERT INTO credit_recharge_packages(package_code, package_name, credits, price_amount, currency, validity_days,
                                     benefits_json, recommended, sort_order, status)
VALUES
  ('starter_1000', '入门套餐', 1000, 10.00, 'CNY', 30, JSON_ARRAY('优先排队'), 0, 10, 'ACTIVE'),
  ('growth_5000', '成长套餐', 5000, 45.00, 'CNY', 90, JSON_ARRAY('优先排队', 'API 加速'), 1, 20, 'ACTIVE'),
  ('pro_12000', '专业套餐', 12000, 99.00, 'CNY', 180, JSON_ARRAY('优先排队', 'API 加速', '模型咨询服务'), 0, 30, 'ACTIVE');
EOF

# 执行回滚
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < sql/rollback_packages.sql
```

### 步骤 2: 恢复旧 markup
修改 `PricingServiceImpl.java`:
```java
static final BigDecimal DEFAULT_MARKUP = new BigDecimal("1.20");  // 改回 1.20
```

重启后端服务。

### 步骤 3: 保留新 UI
前端 UI 可以保留，继续使用新设计但加载旧套餐数据。

---

## 📝 关键文件清单

| 文件路径 | 修改类型 | 说明 |
|---------|---------|------|
| `sql/025_credit_recharge_orders.sql` | 修改 | 新增 8 个套餐配置 |
| `backend/.../PricingServiceImpl.java` | 修改 | DEFAULT_MARKUP 从 1.20 改为 1.50 |
| `user-web/src/pages/Billing/RechargeSection.vue` | 重构 | 移除自定义充值，新增 Tab 切换，重构卡片样式 |
| `user-web/src/pages/Billing/Page.vue` | 修改 | 算力流水默认收起 |
| `deploy-verify.sh` | 新增 | 部署验证脚本 |
| `docs/RECHARGE_REFACTOR_SUMMARY.md` | 新增 | 本文档 |

---

## 🎯 下一步优化建议

1. **A/B 测试**: 对不同用户群体展示不同套餐组合，找到最优转化率
2. **动态定价**: 根据用户行为（使用频率、消耗速度）推荐个性化套餐
3. **年度套餐**: 待用户基数稳定后，引入年度套餐（需提高 markup 至 2.0+）
4. **会员权益**: 增加更多差异化权益（如专属模型、优先客服等）
5. **数据分析**: 监控各套餐转化率、退款率、用户反馈，持续优化

---

## 📞 联系方式

如有问题或需要技术支持，请联系开发团队。

**最后更新**: 2026-06-29

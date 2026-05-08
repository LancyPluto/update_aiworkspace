#!/bin/bash
# AI Tool Market - 自动化测试脚本 (Linux / macOS)

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}"
echo "============================================"
echo "  AI Tool Market - 自动化测试脚本"
echo "============================================"
echo -e "${NC}"

# 检查 Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}[✗] 未找到 Java，请先安装 JDK 17+${NC}"
    exit 1
fi
echo -e "${GREEN}[✓] Java 已安装${NC}"

JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "?(1\.)?\K\d+' || true)
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${YELLOW}[⚠] 建议使用 JDK 17+，当前版本可能不兼容${NC}"
fi

# 检查 Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}[✗] 未找到 Maven，请先安装 Maven 3.8+${NC}"
    exit 1
fi
echo -e "${GREEN}[✓] Maven 已安装${NC}"

# 编译
echo ""
echo -e "${YELLOW}[→] 开始编译项目...${NC}"
echo ""

cd "$(dirname "$0")/backend"

if ! mvn clean compile -q; then
    echo -e "${RED}[✗] 编译失败，请检查代码错误${NC}"
    exit 1
fi
echo -e "${GREEN}[✓] 编译成功${NC}"

# 运行测试
echo ""
echo -e "${YELLOW}[→] 开始运行测试（共 4 个测试类）...${NC}"
echo ""
echo "  - AuthApiTest          认证模块测试"
echo "  - ToolApiTest          工具管理测试"
echo "  - TaskCreditApiTest    任务 + 算力测试"
echo "  - WorkerInternalApiTest Worker 内部 API 测试"
echo ""

START_TIME=$(date +%s)

if mvn clean test; then
    echo ""
    echo -e "${GREEN}"
    echo "============================================"
    echo "  [✓] 全部测试通过！"
    echo "============================================"
    echo -e "${NC}"
else
    echo ""
    echo -e "${RED}"
    echo "============================================"
    echo "  [✗] 存在测试失败，请查看上方日志"
    echo "============================================"
    echo -e "${NC}"
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo -e "${CYAN}[i] 测试耗时：${DURATION} 秒${NC}"
echo -e "${CYAN}[i] 测试报告路径：backend/target/surefire-reports/${NC}"
echo ""

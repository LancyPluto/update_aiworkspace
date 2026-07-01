#!/bin/bash
# 502 错误诊断脚本 - 在服务器上执行此脚本

echo "=========================================="
echo "502 Error Diagnosis Script"
echo "=========================================="
echo ""

cd /root/ai_tool_market/deploy

echo "=== 1. Docker Compose Status ==="
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps | grep -E "backend|nginx|user-web" || echo "No containers found"
echo ""

echo "=== 2. Backend Container Logs (last 50 lines) ==="
docker logs ai-supermarket-backend --tail 50 2>&1 || echo "Backend container not found or not running"
echo ""

echo "=== 3. Environment Variables Check ==="
echo "--- Root .env ---"
if [ -f "/root/ai_tool_market/.env" ]; then
    grep -E '^(APP_PRODUCTION_MODE|JWT_SECRET|INTERNAL_API_TOKEN)=' /root/ai_tool_market/.env || echo "No matching variables found"
else
    echo "File not found: /root/ai_tool_market/.env"
fi
echo ""

echo "--- Deploy .env ---"
if [ -f "/root/ai_tool_market/deploy/.env" ]; then
    grep -E '^(APP_PRODUCTION_MODE|JWT_SECRET|INTERNAL_API_TOKEN)=' /root/ai_tool_market/deploy/.env || echo "No matching variables found"
else
    echo "File not found: /root/ai_tool_market/deploy/.env"
fi
echo ""

echo "--- Container Runtime Env ---"
docker exec ai-supermarket-backend printenv APP_PRODUCTION_MODE JWT_SECRET INTERNAL_API_TOKEN 2>/dev/null || echo "Cannot get env from backend container (not running?)"
echo ""

echo "=== 4. Direct Backend Health Check ==="
echo "Testing http://127.0.0.1:8080/api/health..."
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8080/api/health 2>/dev/null || echo "Connection refused or timeout"
echo ""

echo "Testing http://127.0.0.1/api/health (via nginx)..."
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1/api/health 2>/dev/null || echo "Connection refused or timeout"
echo ""

echo "=== 5. Nginx Access Log (last 10 502 errors) ==="
docker logs ai-supermarket-nginx 2>&1 | grep "502" | tail -10 || echo "No 502 errors in nginx log"
echo ""

echo "=========================================="
echo "Diagnosis Complete"
echo "=========================================="

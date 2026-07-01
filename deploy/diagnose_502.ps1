# 502 错误诊断脚本 - Windows PowerShell 版本
# 使用方法: .\diagnose_502.ps1

$server = "8.134.93.203"
$user = "root"
$password = "KeChuangDianAi17728033019"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "502 Error Diagnosis Script" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# 创建 SSH 会话（使用 plink 或 winscp）
# 由于 PowerShell 原生 SSH 不支持密码，我们生成一个远程执行脚本

$remoteScript = @'
#!/bin/bash
echo "=== 1. Docker Compose Status ==="
cd /root/ai_tool_market/deploy
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps | grep -E "backend|nginx|user-web" || echo "No containers found"
echo ""

echo "=== 2. Backend Container Logs (last 100 lines) ==="
docker logs ai-supermarket-backend --tail 100 2>&1 || echo "Backend container not found or not running"
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
'@

Write-Host "请在服务器上手动执行以下命令：" -ForegroundColor Yellow
Write-Host ""
Write-Host "ssh root@$server" -ForegroundColor Green
Write-Host "然后复制并粘贴以下内容到服务器终端：" -ForegroundColor Yellow
Write-Host ""
Write-Host $remoteScript -ForegroundColor White
Write-Host ""
Write-Host "或者，将上述内容保存为 diagnose.sh 后执行：" -ForegroundColor Yellow
Write-Host "chmod +x diagnose.sh && ./diagnose.sh" -ForegroundColor Green
Write-Host ""
Write-Host "把输出结果发给我，我会帮你分析问题。" -ForegroundColor Cyan

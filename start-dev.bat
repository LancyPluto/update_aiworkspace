@echo off
chcp 65001 >nul
title AI Tool Market - 一键启动开发环境

echo ============================================
echo   AI Tool Market - 一键启动开发环境
echo ============================================
echo.

REM ===== 前置检查 =====

REM 检查 Docker
where docker >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 Docker，请先安装 Docker Desktop
    pause
    exit /b 1
)
echo [✓] Docker 已安装

REM 检查 Java
where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 Java，请先安装 JDK 17+
    pause
    exit /b 1
)
echo [✓] Java 已安装

REM 检查 Maven
where mvn >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 Maven，请先安装 Maven 3.8+
    pause
    exit /b 1
)
echo [✓] Maven 已安装

REM 检查 Node / npm
where npm >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 npm，请先安装 Node.js
    pause
    exit /b 1
)
echo [✓] npm 已安装

echo.
echo ============================================
echo   [1/6] 启动 Docker 容器 (MySQL + Redis)
echo ============================================
echo.

cd /d "%~dp0deploy"
docker-compose up -d mysql redis 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] Docker 容器启动失败
    pause
    exit /b 1
)
echo [✓] MySQL + Redis 已启动
echo.

REM 等待数据库就绪
echo [→] 等待 MySQL 就绪...
:wait_mysql
docker exec ai-supermarket-mysql mysqladmin ping -uroot -proot123456 --silent 2>nul
if %ERRORLEVEL% NEQ 0 (
    timeout /t 2 /nobreak >nul
    goto wait_mysql
)
echo [✓] MySQL 就绪

echo.
echo ============================================
echo   [2/6] 安装用户端依赖 user-web（如需要）
echo ============================================
echo.
cd /d "%~dp0user-web"
if not exist "node_modules" (
    echo [→] 安装 npm 依赖...
    call npm install 2>&1
    if %ERRORLEVEL% NEQ 0 (
        echo [✗] user-web npm install 失败
        pause
        exit /b 1
    )
    echo [✓] user-web 依赖安装完成
) else (
    echo [✓] user-web 依赖已存在，跳过安装
)

echo.
echo ============================================
echo   [3/6] 安装管理端依赖 admin-frontend（如需要）
echo ============================================
echo.
cd /d "%~dp0admin-frontend"
if not exist "node_modules" (
    echo [→] 安装 npm 依赖...
    call npm install 2>&1
    if %ERRORLEVEL% NEQ 0 (
        echo [✗] admin-frontend npm install 失败
        pause
        exit /b 1
    )
    echo [✓] admin-frontend 依赖安装完成
) else (
    echo [✓] admin-frontend 依赖已存在，跳过安装
)

echo.
echo ============================================
echo   [4/6] 启动后端服务 (新窗口)
echo ============================================
echo.
start "Backend" cmd /c "cd /d %~dp0backend && echo [Backend] 启动中... && mvn spring-boot:run && pause || pause"
echo [→] 后端正在启动（新窗口），请等待就绪...
echo.

REM 等待后端就绪（最多约 60 秒）
echo [→] 等待后端就绪...
set BACKEND_READY=0
for /l %%i in (1,1,30) do (
    >nul 2>&1 curl -s http://localhost:8080/api/health && set BACKEND_READY=1 && goto backend_ready
    timeout /t 2 /nobreak >nul
)
:backend_ready
if %BACKEND_READY% EQU 1 (
    echo [✓] 后端就绪（端口 8080）
) else (
    echo [⚠] 后端启动可能较慢，请手动检查 http://localhost:8080/api/health
)

echo.
echo ============================================
echo   [5/6] 启动用户端 user-web (新窗口)
echo ============================================
echo.
start "User-Web" cmd /c "cd /d %~dp0user-web && echo [User-Web] 启动中... && npm run dev && pause || pause"
echo [✓] 用户端正在启动（新窗口 User-Web）

echo.
echo ============================================
echo   [6/6] 启动管理后台 admin-frontend (新窗口)
echo ============================================
echo.
start "Admin-Frontend" cmd /c "cd /d %~dp0admin-frontend && echo [Admin-Frontend] 启动中... && npm run dev && pause || pause"
echo [✓] 管理后台正在启动（新窗口 Admin-Frontend）

echo.
echo ============================================
echo   启动完成！
echo ============================================
echo.
echo   后端 API:     http://localhost:8080
echo   健康检查:     http://localhost:8080/api/health
echo   用户端:       http://localhost:5173  （窗口 User-Web）
echo   管理后台:     http://localhost:5174  （窗口 Admin-Frontend）
echo.
echo   后端日志在 "Backend" 窗口查看
echo.
echo   关闭此窗口不会影响正在运行的服务
echo.
pause

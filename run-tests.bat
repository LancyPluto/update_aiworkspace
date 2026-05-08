@echo off
chcp 65001 >nul
title AI Tool Market - 自动化测试

echo ============================================
echo   AI Tool Market - 自动化测试脚本
echo ============================================
echo.

REM 检查 Java
where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 Java，请先安装 JDK 17+
    pause
    exit /b 1
)
echo [✓] Java 已安装
java -version 2>&1 | findstr "version" >nul && java -version 2>&1 | findstr "17\|18\|19\|20\|21\|22" >nul
if %ERRORLEVEL% NEQ 0 (
    echo [⚠] 建议使用 JDK 17+，当前版本可能不兼容
)

REM 检查 Maven
where mvn >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 未找到 Maven，请先安装 Maven 3.8+
    pause
    exit /b 1
)
echo [✓] Maven 已安装

echo.
echo [→] 开始编译项目...
echo.
cd /d "%~dp0backend"

call mvn clean compile -q
if %ERRORLEVEL% NEQ 0 (
    echo [✗] 编译失败，请检查代码错误
    pause
    exit /b 1
)
echo [✓] 编译成功

echo.
echo [→] 开始运行测试（共 4 个测试类）...
echo.
echo   - AuthApiTest         认证模块测试
echo   - ToolApiTest         工具管理测试
echo   - TaskCreditApiTest   任务 + 算力测试
echo   - WorkerInternalApiTest Worker 内部 API 测试
echo.

set START_TIME=%TIME%

call mvn clean test 2>&1

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo   [✓] 全部测试通过！
    echo ============================================
) else (
    echo.
    echo ============================================
    echo   [✗] 存在测试失败，请查看上方日志
    echo ============================================
)

echo.
echo [i] 测试报告路径：backend\target\surefire-reports\
echo.
pause

@echo off
setlocal
chcp 65001 >nul

set "ROOT=%~dp0"
cd /d "%ROOT%"

echo ============================================
echo   Local LLM Guard 打包脚本
echo ============================================
echo.

rem ---------- 检查 Maven ----------
where mvn >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 mvn，请先安装 Maven 并加入 PATH。
    exit /b 1
)

echo [1/3] 构建前端...
if exist "%ROOT%frontend" (
    pushd "%ROOT%frontend"
    if not exist node_modules (
        echo   首次构建，安装前端依赖...
        call npm install || goto :fail
    )
    call npm run build || goto :fail
    popd
) else (
    echo   未找到 frontend 目录，跳过前端构建。
)

echo.
echo [2/3] Maven 打包（跳过测试）...
call mvn clean package -DskipTests || goto :fail

echo.
echo [3/3] 检查构建产物...
if not exist "%ROOT%target\vllm-gateway.jar" (
    echo [错误] 未生成 target\vllm-gateway.jar。
    goto :fail
)

echo.
echo [完成] 打包成功: %ROOT%target\vllm-gateway.jar
exit /b 0

:fail
popd 2>nul
echo.
echo [错误] 构建失败，请检查上方输出。
exit /b 1

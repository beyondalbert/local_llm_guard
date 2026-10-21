@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "CONFIG_FILE=%ROOT%deploy.conf"
set "CLI_TARGET="
set "FORCE_LOCAL=0"

rem ---------- 解析命令行参数 ----------
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--config" (
    if "%~2"=="" (
        echo [错误] --config 需要指定配置文件路径。
        exit /b 1
    )
    set "CONFIG_FILE=%~2"
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--local" (
    set "FORCE_LOCAL=1"
    shift
    goto :parse_args
)
set "CLI_TARGET=%~1"
shift
goto :parse_args
:args_done

rem ---------- 读取打包配置文件 ----------
if exist "%CONFIG_FILE%" (
    echo [配置] 读取 %CONFIG_FILE%
    call :load_config "%CONFIG_FILE%"
) else (
    echo [配置] 未找到 %CONFIG_FILE%，使用默认本地打包。
)

rem ---------- 决定部署模式 ----------
set "IS_REMOTE=0"
set "LOCAL_TARGET=%ROOT%deploy"

if not "!FORCE_LOCAL!"=="1" (
    if not "!CLI_TARGET!"=="" (
        echo !CLI_TARGET! | findstr /C:"@" >nul
        if not errorlevel 1 (
            set "IS_REMOTE=1"
            set "SSH_TARGET=!CLI_TARGET!"
        ) else (
            set "LOCAL_TARGET=!CLI_TARGET!"
        )
    ) else if /i "!DEPLOY_MODE!"=="remote" (
        set "IS_REMOTE=1"
    )
)

rem ---------- 解析远程目标 ----------
if "!IS_REMOTE!"=="1" (
    if defined SSH_TARGET (
        for /f "tokens=1,* delims=:" %%a in ("!SSH_TARGET!") do (
            set "SSH_USERHOST=%%a"
            set "REMOTE_PATH=%%b"
        )
        for /f "tokens=1,* delims=@" %%a in ("!SSH_USERHOST!") do (
            set "SSH_USER=%%a"
            set "SSH_HOST=%%b"
        )
    ) else (
        set "SSH_USER=!DEPLOY_USER!"
        set "SSH_HOST=!DEPLOY_HOST!"
        set "REMOTE_PATH=!DEPLOY_PATH!"
    )
    if "!REMOTE_PATH!"=="" (
        echo [错误] 远程路径为空，请检查配置文件 DEPLOY_PATH。
        exit /b 1
    )
    if "!SSH_HOST!"=="" (
        echo [错误] 远程主机为空，请检查配置文件 DEPLOY_HOST。
        exit /b 1
    )
)

echo ============================================
echo   Local LLM Guard 部署脚本
if "!IS_REMOTE!"=="1" (
    echo   模式: 远程部署
    echo   目标: !SSH_USER!@!SSH_HOST!:!REMOTE_PATH!
) else (
    echo   模式: 本地打包
    echo   目标: !LOCAL_TARGET!
)
echo ============================================
echo.

rem ---------- 打包 ----------
call "%ROOT%build.bat" || exit /b 1

if not exist "%ROOT%target\vllm-gateway.jar" (
    echo [错误] 未找到 target\vllm-gateway.jar。
    exit /b 1
)

rem ---------- 本地暂存 ----------
set "STAGE=%ROOT%deploy"
if not exist "%STAGE%" mkdir "%STAGE%"
if not exist "%STAGE%\data" mkdir "%STAGE%\data"

echo [部署] 准备部署文件...
copy /Y "%ROOT%target\vllm-gateway.jar" "%STAGE%\vllm-gateway.jar" >nul || goto :fail
copy /Y "%ROOT%start.bat" "%STAGE%\start.bat" >nul || goto :fail
copy /Y "%ROOT%start.sh" "%STAGE%\start.sh" >nul || goto :fail
rem 部署包一并带上完整版系统说明
if exist "%ROOT%README.md" copy /Y "%ROOT%README.md" "%STAGE%\README.md" >nul
if exist "%ROOT%deploy.conf.example" copy /Y "%ROOT%deploy.conf.example" "%STAGE%\deploy.conf.example" >nul

rem 保证 Linux 脚本为 LF 行尾（避免 bash\r 报错）
echo [部署] 规范化 start.sh 行尾为 LF...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%STAGE%\start.sh'; $t=[IO.File]::ReadAllText($p); $t=$t -replace '\r\n',([string][char]10); [IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))" || goto :fail

(
    echo Local LLM Guard 部署包
    echo.
    echo 目录说明:
    echo   vllm-gateway.jar  可执行 JAR
    echo   start.bat         Windows 启动脚本
    echo   start.sh          Linux 启动脚本
    echo   README.md         完整功能与使用说明
    echo   data\             SQLite 数据库目录（首次运行自动生成 vllm_gateway.db）
    echo.
    echo 快速启动（详见 README.md）:
    echo   Windows: start.bat    （停止 start.bat stop，状态 start.bat status）
    echo   Linux:    ./start.sh  参数为 start / restart / stop / status
    echo.
    echo 默认管理员: admin / Admin@123456
    echo 管理台:    http://127.0.0.1:8080
) > "%STAGE%\README.txt"

if "!IS_REMOTE!"=="1" goto :remote_deploy

echo.
echo [完成] 本地部署包已生成: %STAGE%
exit /b 0

rem ================= 远程部署 =================
:remote_deploy
where scp >nul 2>&1 || (echo [错误] 未找到 scp，请安装 OpenSSH 客户端并加入 PATH。 & exit /b 1)
where ssh >nul 2>&1 || (echo [错误] 未找到 ssh，请安装 OpenSSH 客户端并加入 PATH。 & exit /b 1)

set "SSH_PORT=22"
if not "!DEPLOY_SSH_PORT!"=="" set "SSH_PORT=!DEPLOY_SSH_PORT!"
set "SSH_KEY="
if not "!DEPLOY_SSH_KEY!"=="" set "SSH_KEY=!DEPLOY_SSH_KEY:\=/!"

echo.
echo [远程] 上传部署文件到 !SSH_USER!@!SSH_HOST!:!REMOTE_PATH! ...
call :do_scp "%STAGE%\vllm-gateway.jar" "!REMOTE_PATH!/vllm-gateway.jar" || goto :fail
call :do_scp "%STAGE%\start.sh" "!REMOTE_PATH!/start.sh" || goto :fail
call :do_scp "%STAGE%\README.txt" "!REMOTE_PATH!/README.txt" || goto :fail

rem ---------- 组装远程环境变量 ----------
set "REMOTE_ENV="
if not "!DEPLOY_SERVER_PORT!"=="" set "REMOTE_ENV=SERVER_PORT='!DEPLOY_SERVER_PORT!'"
if not "!DEPLOY_UPSTREAM_URL!"=="" (
    if defined REMOTE_ENV set "REMOTE_ENV=!REMOTE_ENV! "
    set "REMOTE_ENV=!REMOTE_ENV!VLLM_UPSTREAM_URL='!DEPLOY_UPSTREAM_URL!'"
)

set "RESTART_CMD=cd '!REMOTE_PATH!' && chmod +x start.sh"
if defined REMOTE_ENV (
    set "RESTART_CMD=!RESTART_CMD! && !REMOTE_ENV! ./start.sh restart"
) else (
    set "RESTART_CMD=!RESTART_CMD! && ./start.sh restart"
)

echo [远程] 重启服务...
call :do_ssh "!RESTART_CMD!" || goto :fail

echo.
echo [完成] 远程部署成功: !SSH_USER!@!SSH_HOST!:!REMOTE_PATH!
exit /b 0

rem ---------- 子程序：scp 上传 ----------
:do_scp
if defined SSH_KEY (
    scp -i "!SSH_KEY!" -P "!SSH_PORT!" "%~1" "!SSH_USER!@!SSH_HOST!:%~2"
) else (
    scp -P "!SSH_PORT!" "%~1" "!SSH_USER!@!SSH_HOST!:%~2"
)
exit /b %errorlevel%

rem ---------- 子程序：ssh 执行 ----------
:do_ssh
if defined SSH_KEY (
    ssh -i "!SSH_KEY!" -p "!SSH_PORT!" "!SSH_USER!@!SSH_HOST!" "%~1"
) else (
    ssh -p "!SSH_PORT!" "!SSH_USER!@!SSH_HOST!" "%~1"
)
exit /b %errorlevel%

:fail
echo.
echo [错误] 部署失败。
exit /b 1

rem ================= 读取配置文件 =================
:load_config
set "CFG_FILE=%~1"
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("!CFG_FILE!") do (
    set "CFG_KEY=%%a"
    set "CFG_VAL=%%b"
    for /f "tokens=1 delims= " %%k in ("!CFG_KEY!") do set "CFG_KEY=%%k"
    if defined CFG_KEY if not "!CFG_KEY!"=="" set "!CFG_KEY!=!CFG_VAL!"
)
exit /b

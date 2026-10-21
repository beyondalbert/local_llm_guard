@echo off
setlocal
chcp 65001 >nul
title Local LLM Guard

set "ROOT=%~dp0"
cd /d "%ROOT%"

rem ---------- 命令：stop / status ----------
if /i "%~1"=="stop" goto :do_stop
if /i "%~1"=="status" goto :do_status

rem ---------- 查找 Java 17+ ----------
set "JAVA_EXE="
set "JAVA_VER="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" call :try_java "%JAVA_HOME%\bin\java.exe"

if not defined JAVA_EXE (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*" "%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Java\jdk-17*" "%ProgramFiles%\Java\jdk-21*" "%ProgramFiles%\Microsoft\jdk-17*" "D:\tools\jdk17") do (
        if not defined JAVA_EXE if exist "%%~D\bin\java.exe" call :try_java "%%~D\bin\java.exe"
    )
)

if not defined JAVA_EXE (
    where java >nul 2>&1
    if not errorlevel 1 call :try_java "java"
)

if not defined JAVA_EXE (
    echo [错误] 未找到 Java 17 或更高版本。
    echo   请安装 JDK 17+，或设置 JAVA_HOME 指向 JDK 17+ 的安装目录。
    exit /b 1
)

rem ---------- 定位 JAR ----------
set "JAR=%ROOT%vllm-gateway.jar"
if not exist "%JAR%" set "JAR=%ROOT%target\vllm-gateway.jar"
if not exist "%JAR%" (
    echo [错误] 未找到 vllm-gateway.jar，请先运行 build.bat 打包，或将其放到脚本同目录。
    exit /b 1
)

if not exist "%ROOT%data" mkdir "%ROOT%data"
set "PID_FILE=%ROOT%data\vllm-gateway.pid"

rem 数据库固定落在脚本事目录下的 data\，与启动时所在目录无关
if not defined SPRING_DATASOURCE_URL set "SPRING_DATASOURCE_URL=jdbc:sqlite:%ROOT:\=/%data/vllm_gateway.db"

rem ---------- 组装启动参数 ----------
set "ARGS=--spring.profiles.active=prod"
if not "%SERVER_PORT%"=="" set "ARGS=%ARGS% --server.port=%SERVER_PORT%"
if not "%VLLM_UPSTREAM_URL%"=="" set "ARGS=%ARGS% --vllm.upstream-url=%VLLM_UPSTREAM_URL%"
if not "%~1"=="" set "ARGS=%ARGS% --vllm.upstream-url=%~1"

echo [Java] %JAVA_EXE%  (%JAVA_VER%)
echo [JAR ] %JAR%
echo [Args] %ARGS%
echo [DB  ] %SPRING_DATASOURCE_URL%
echo [PID ] %PID_FILE%
echo [启动] 按 Ctrl+C 停止服务；另开窗口运行 start.bat stop 可停止。
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:VLLM_ARGS='%ARGS%'; $p=Start-Process -FilePath '%JAVA_EXE%' -ArgumentList (@('-jar','%JAR%') + @($env:VLLM_ARGS -split ' ')) -NoNewWindow -PassThru; Set-Content -Path '%PID_FILE%' -Value $p.Id; $p.WaitForExit(); Remove-Item -Path '%PID_FILE%' -ErrorAction SilentlyContinue"

exit /b %errorlevel%

rem ---------- 停止服务 ----------
:do_stop
set "PID_FILE=%ROOT%data\vllm-gateway.pid"
if not exist "%PID_FILE%" goto :stop_no_file
set /p PID=<"%PID_FILE%"
tasklist /FI "PID eq %PID%" 2>nul | findstr /C:"%PID%" >nul
if errorlevel 1 (
    echo [提示] PID 文件存在但进程已退出，清理 PID 文件。
    del "%PID_FILE%" >nul 2>&1
    exit /b 0
)
echo [停止] PID %PID%
taskkill /PID %PID% /T /F >nul 2>&1
del "%PID_FILE%" >nul 2>&1
echo [完成] 服务已停止。
exit /b 0

:stop_no_file
echo [提示] 未找到 PID 文件，尝试按 JAR 路径查找并停止...
set "JAR=%ROOT%vllm-gateway.jar"
if not exist "%JAR%" set "JAR=%ROOT%target\vllm-gateway.jar"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$j='%JAR%'; $ps=Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($j) }; if($ps){ $ps | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Output ('[停止] PID ' + $_.ProcessId) }; Write-Output '[完成] 服务已停止。' } else { Write-Output '[提示] 未找到运行中的服务。' }"
exit /b 0

rem ---------- 查看状态 ----------
:do_status
set "PID_FILE=%ROOT%data\vllm-gateway.pid"
if not exist "%PID_FILE%" (
    echo [状态] 未运行
    exit /b 0
)
set /p PID=<"%PID_FILE%"
tasklist /FI "PID eq %PID%" 2>nul | findstr /C:"%PID%" >nul
if errorlevel 1 (
    echo [状态] 未运行（PID 文件残留，进程已退出）
    exit /b 0
)
echo [状态] 运行中 (PID %PID%)
exit /b 0

rem ---------- 子程序：尝试某个 java 并校验版本 ----------
:try_java
set "CAND=%~1"
set "VTMP=%TEMP%\vllm_java_ver.tmp"
"%CAND%" -version >nul 2>"%VTMP%"
if errorlevel 1 exit /b 1
set "JVER="
for /f "tokens=3" %%v in ('findstr /i "version" "%VTMP%"') do set "JVER=%%~v"
del "%VTMP%" >nul 2>&1
if not defined JVER exit /b 1
for /f "delims=." %%m in ("%JVER%") do set "JMAJOR=%%m"
if %JMAJOR% LSS 17 exit /b 1
set "JAVA_EXE=%CAND%"
set "JAVA_VER=%JVER%"
exit /b 0

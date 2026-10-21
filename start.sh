#!/usr/bin/env bash
set -euo pipefail

APP_NAME="local-llm-guard"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
JAR="$ROOT/vllm-gateway.jar"
PID_FILE="$ROOT/$APP_NAME.pid"
LOG_FILE="$ROOT/logs/console.log"

mkdir -p "$ROOT/data" "$ROOT/logs"

# 数据库固定落在脚本事目录下的 data/，与启动时所在目录无关
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:sqlite:$ROOT/data/vllm_gateway.db}"

# ---------- 查找 Java 17+ ----------
find_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"
    return 0
  fi
  command -v java >/dev/null 2>&1 && echo "$(command -v java)" && return 0
  return 1
}

JAVA_EXE="$(find_java)" || { echo "[错误] 未找到 Java 17+，请安装 JDK 17+ 或设置 JAVA_HOME。" >&2; exit 1; }

# ---------- 组装启动参数 ----------
ARGS="--spring.profiles.active=prod"
if [ -n "${SERVER_PORT:-}" ]; then ARGS="$ARGS --server.port=$SERVER_PORT"; fi
if [ -n "${VLLM_UPSTREAM_URL:-}" ]; then ARGS="$ARGS --vllm.upstream-url=$VLLM_UPSTREAM_URL"; fi

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

start() {
  if is_running; then
    echo "[提示] 服务已在运行 (PID $(cat "$PID_FILE"))"
    return 0
  fi
  echo "[Java] $JAVA_EXE"
  echo "[Args] $ARGS"
  echo "[DB  ] $SPRING_DATASOURCE_URL"
  cd "$ROOT"
  nohup "$JAVA_EXE" -jar "$JAR" $ARGS >>"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "[启动] PID $(cat "$PID_FILE")，日志: $LOG_FILE"
}

stop() {
  if ! is_running; then
    echo "[提示] 服务未在运行"
    rm -f "$PID_FILE"
    return 0
  fi
  PID="$(cat "$PID_FILE")"
  echo "[停止] PID $PID"
  kill "$PID" 2>/dev/null || true
  for i in $(seq 1 30); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "[强制] 强制终止 PID $PID"
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "[完成] 服务已停止"
}

status() {
  if is_running; then
    echo "[状态] 运行中 (PID $(cat "$PID_FILE"))"
  else
    echo "[状态] 未运行"
  fi
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  *)
    echo "用法: $0 {start|stop|restart|status}"
    echo "环境变量:"
    echo "  SERVER_PORT        管理端口，默认 8080"
    echo "  VLLM_UPSTREAM_URL  vLLM 上游地址，默认 http://127.0.0.1:8000/v1"
    exit 1
    ;;
esac

#!/usr/bin/env bash
#
# AGI-Buffett 一键重启:关闭旧实例 -> 编译 -> 打包 -> 启动 Web 服务。
#
# 用法:
#   ./restart.sh            # 默认端口 8080
#   ./restart.sh 8090       # 指定端口
#   ./restart.sh 8090 stop  # 仅关闭,不启动
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${1:-8080}"
ACTION="${2:-restart}"
PID_FILE="$ROOT/.buffett-web.pid"
LOG_FILE="$ROOT/buffett-web.log"
DATA_DIR="$ROOT/data"

# ---- JAVA_HOME:优先环境变量,其次 macOS 上的 JDK 8 ----
if [ -z "${JAVA_HOME:-}" ]; then
  if /usr/libexec/java_home -v 1.8 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
  elif /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi
export JAVA_HOME
echo "JAVA_HOME = ${JAVA_HOME:-(使用 PATH 中的 java)}"

# ---- 1. 关闭旧实例 ----
stop_server() {
  echo "[1/4] 关闭旧实例 ..."
  # 1) 按 PID 文件
  if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "${OLD_PID:-}" ] && kill -0 "$OLD_PID" 2>/dev/null; then
      kill "$OLD_PID" 2>/dev/null || true
      sleep 1; kill -9 "$OLD_PID" 2>/dev/null || true
      echo "      已停止 PID $OLD_PID"
    fi
    rm -f "$PID_FILE"
  fi
  # 2) 按端口兜底
  if command -v lsof >/dev/null 2>&1; then
    PORT_PIDS="$(lsof -ti tcp:"$PORT" 2>/dev/null || true)"
    [ -n "$PORT_PIDS" ] && echo "$PORT_PIDS" | xargs kill -9 2>/dev/null || true
  fi
  # 3) 按进程特征兜底
  pkill -9 -f "com.agibuffett.web.WebServer" 2>/dev/null || true
  pkill -9 -f "buffett-web/target/buffett-web" 2>/dev/null || true
}

stop_server

if [ "$ACTION" = "stop" ]; then
  echo "已关闭(stop 模式,不启动)。"
  exit 0
fi

# ---- 2 & 3. 编译 + 打包(mvn package 含测试与 shade 可执行 jar)----
echo "[2/4] 编译 + [3/4] 打包 ..."
cd "$ROOT"
mvn -q clean package

JAR="$(ls "$ROOT"/buffett-web/target/buffett-web-*.jar 2>/dev/null | grep -v original | head -1)"
if [ -z "$JAR" ]; then
  echo "✗ 未找到可执行 jar,打包可能失败。" >&2
  exit 1
fi
echo "      jar = $JAR"

# ---- 4. 启动 ----
echo "[4/4] 启动 Web 服务 (端口 $PORT) ..."
nohup java -jar "$JAR" "$PORT" "$DATA_DIR" > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"

# 等待就绪(探测 /api/watchlist)
for i in $(seq 1 15); do
  if curl -fs "http://localhost:$PORT/api/watchlist" >/dev/null 2>&1; then
    echo ""
    echo "✓ 启动成功  PID=$NEW_PID"
    echo "  访问:   http://localhost:$PORT"
    echo "  日志:   $LOG_FILE"
    echo "  数据:   $DATA_DIR"
    echo "  关闭:   ./restart.sh $PORT stop"
    exit 0
  fi
  sleep 1
done

echo "✗ 启动后 15s 内未就绪,请查看日志:$LOG_FILE" >&2
tail -20 "$LOG_FILE" >&2 || true
exit 1

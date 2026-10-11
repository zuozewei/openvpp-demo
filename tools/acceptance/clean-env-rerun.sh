#!/usr/bin/env bash
# 干净环境复跑（专栏第 59 篇「部署与恢复」验收格：干净环境复跑记录）
# 用法：./clean-env-rerun.sh [克隆目录] [空 .m2 目录]
#   默认 克隆目录=/c/Temp/openvpp-clean-verify  空 .m2 目录=/c/Temp/openvpp-clean-m2
#
# 口径：
#   - 「新克隆目录」：git clone --depth 1 已提交基线（master HEAD），不含工作树未提交改动；
#   - 「空 .m2 以外的最简环境」：-Dmaven.repo.local 指向空目录，依赖全量经 settings-openvpp.xml
#     公共镜像下载，模拟新机器首跑；
#   - 应用以默认本地形态（H2 文件库 + 本地模拟网关）启动，数据源 url 覆盖到克隆目录内，
#     与宿主演示库完全隔离；
#   - 冒烟链路不依赖本次新增端点（干净克隆为已提交基线）：ping → demo/run → bills →
#     同键幂等重放 → demo/reset，与 smoke-demo.sh 的既有端子集一致。
#
# 退出码非零即失败；全部输出由调用方 tee 留档（命令/输入/预期/实际见 docs/acceptance/ 记录）。
set -euo pipefail

WORK="${1:-/c/Temp/openvpp-clean-verify}"
M2="${2:-/c/Temp/openvpp-clean-m2}"
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
PORT="${PORT:-8080}"
API="http://127.0.0.1:$PORT/api/v1"
APP_PID=""

step() { echo ""; echo "== $1"; }
check() {
  if [ "$2" = "$3" ]; then echo "[PASS] $1"; else echo "[FAIL] $1  预期=$2  实际=$3"; exit 1; fi
}

stop_app() { # Git Bash 下 $! 为 MSYS pid，taskkill 需 Windows pid：按监听端口反查最可靠
  local pid
  pid=$(netstat -ano | grep ":$PORT " | grep LISTENING | awk '{print $NF}' | head -1)
  if [ -n "$pid" ]; then taskkill //PID "$pid" //F 2>/dev/null || true; fi
  if [ -n "$APP_PID" ]; then kill -9 "$APP_PID" 2>/dev/null || true; fi
}
trap stop_app EXIT

step "0. 环境信息"
echo "date=$(date '+%Y-%m-%d %H:%M:%S %z')"
echo "java=$(java -version 2>&1 | head -1)"
echo "mvn=$(mvn -version 2>&1 | head -1)"
echo "git_src=$SRC"

step "1. 新克隆目录（已提交基线，不含未提交改动）"
rm -rf "$WORK" "$M2"
mkdir -p "$WORK" "$(dirname "$M2")"
git clone --depth 1 "$SRC" "$WORK"
cd "$WORK"
echo "cloned HEAD: $(git log --oneline -1)"

step "2. 空 .m2 全量构建：mvn -s settings-openvpp.xml -Dmaven.repo.local=<空目录> install -DskipTests"
M2_MIXED="$(cygpath -m "$M2" 2>/dev/null || echo "$M2")"
mvn -s settings-openvpp.xml "-Dmaven.repo.local=$M2_MIXED" install -DskipTests

step "3. 启动应用（默认本地形态，H2 库隔离在克隆目录内）"
DB_DIR="$(cygpath -m "$WORK" 2>/dev/null || echo "$WORK")/h2-data"
mkdir -p "$DB_DIR"
nohup java -jar openvpp-app/target/openvpp-app-1.0.0.jar \
  --spring.datasource.url="jdbc:h2:file:$DB_DIR/openvpp-db;AUTO_SERVER=TRUE;LOCK_TIMEOUT=10000" \
  --server.port="$PORT" > "$WORK/app-run.log" 2>&1 &
APP_PID=$!
echo "应用 pid=$APP_PID → 日志 $WORK/app-run.log"

deadline=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if curl -fsS "$API/system/ping" 2>/dev/null | grep -q '"status":"UP"'; then break; fi
  sleep 2
done
ping=$(curl -fsS "$API/system/ping")
echo "$ping"
check "骨架自检" "UP" "$(echo "$ping" | grep -io '"status":"UP"' | head -1 | cut -d'"' -f4)"

step "4. 演示链路冒烟（既有端点）"
RID="clean-$(date +%H%M%S)"
run=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
check "净实收" "1200.00" "$(echo "$run" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)"
check "首次非重放" "false" "$(echo "$run" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
bills=$(curl -fsS "$API/bills?responseId=$RID")
check "V1 账单条数" "5" "$(echo "$bills" | grep -io '"BILL_TYPE"' | wc -l)"
replay=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
check "同键重放" "true" "$(echo "$replay" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
tasks=$(curl -fsS "$API/tasks" | grep -io "\"RESPONSE_ID\":\"$RID\"" | wc -l || true)
check "任务表唯一行" "1" "$tasks"
reset=$(curl -fsS -X POST "$API/demo/reset")
echo "reset: $reset"
after=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
check "重置后全新执行" "false" "$(echo "$after" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
curl -fsS -X POST "$API/demo/reset" >/dev/null

echo ""
echo "CLEAN ENV RERUN OK（克隆基线 $(git log --oneline -1)）"

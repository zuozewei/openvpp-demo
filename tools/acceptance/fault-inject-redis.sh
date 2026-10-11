#!/usr/bin/env bash
# Redis 故障注入与进程中断恢复验证（专栏第 59 篇「部署与恢复」验收格底座）
# 用法：./fault-inject-redis.sh
# 前置：
#   1. 仓库根目录执行（读取 docker-compose.yml 的 mysql/redis 服务）；
#   2. openvpp-app jar 已构建：mvn -s settings-openvpp.xml install -DskipTests
#      （脚本自动检查，缺失即报错退出并给出构建命令）；
#   3. 宿主机 8080/3306/6379 未被占用（compose 端口映射 + 本地应用）。
#
# 形态：docker compose 起 MySQL + Redis 中间件，本地 jar 以 docker profile 启动并连容器
#       （application-docker.yml：MySQL 持久化 + Redis 前置幂等启用）。
#
# 验证步骤（每步断言失败即非零退出）：
#   1  health UP（redis UP）——容器链路就绪
#   2  run 键A 闭环——净实收 1200.00 落 MySQL，结果缓存写 Redis
#   3  docker compose stop redis——制造 Redis 不可用
#   4  health DEGRADED（redis DOWN）——退化语义在线可见，应用仍可用
#   5  run 键B（新键）——Redis 不可用下业务不 500，数据库兜底路径落库
#   6  run 键A 同键——缓存读取失败退化未命中 → 数据库路径幂等重放，不重复出账
#   7  docker compose start redis——故障恢复
#   8  kill -9 应用（进程中断）→ 重启——同键重放金额一致、账单条数不增（状态一致性）
#   9  POST /demo/reset 重置——库表 + 运行态同清后同键为全新执行（重置后干净复跑）
#
# 收尾：脚本结束自动停止应用进程；中间件容器保留（docker compose down 由调用方决定）。
set -euo pipefail

cd "$(dirname "$0")/../.."   # 仓库根目录
API="http://127.0.0.1:8080/api/v1"
JAR="openvpp-app/target/openvpp-app-1.0.0.jar"
APP_LOG="${FAULT_APP_LOG:-/tmp/openvpp-fault-app.log}"
KEY_A="fault-a-$(date +%H%M%S)"
KEY_B="fault-b-$(date +%H%M%S)"
APP_PID=""
APP_PORT=8080
# MySQL 宿主端口：默认 3306；宿主 3306 被占时以 MYSQL_PORT=13306 运行本脚本——
# 脚本自动以 MYSQL_HOST_PORT=13306 重建容器（compose 端口已参数化），应用注入同一端口
MYSQL_PORT="${MYSQL_PORT:-3306}"
export MYSQL_HOST_PORT="$MYSQL_PORT"

step() { echo ""; echo "== $1"; }
check() { # $1 断言名  $2 预期  $3 实际
  if [ "$2" = "$3" ]; then
    echo "[PASS] $1"
  else
    echo "[FAIL] $1  预期=$2  实际=$3"
    exit 1
  fi
}

status_of() { curl -fsS "$API/system/health" | grep -io '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4; }

wait_status() { # $1 期望状态  $2 超时秒
  local deadline=$(( $(date +%s) + ${2:-60} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local s
    s=$(status_of 2>/dev/null || echo "CONNECTING")
    if [ "$s" = "$1" ]; then return 0; fi
    sleep 2
  done
  echo "[FAIL] 等待状态 $1 超时（当前 $(status_of 2>/dev/null || echo 不可达)）"
  return 1
}

start_app() {
  MYSQL_HOST=127.0.0.1 MYSQL_PORT=$MYSQL_PORT MYSQL_DATABASE=openvpp_demo \
  MYSQL_USER=root MYSQL_PASSWORD=openvpp-demo \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6379 \
  SPRING_PROFILES_ACTIVE=docker \
    nohup java -jar "$JAR" > "$APP_LOG" 2>&1 &
  APP_PID=$!
  echo "应用已启动 pid=$APP_PID → 日志 $APP_LOG"
}

stop_app() { # Git Bash 下 $! 为 MSYS pid，taskkill 需 Windows pid：按监听端口反查最可靠
  local pid
  pid=$(netstat -ano | grep ":$APP_PORT " | grep LISTENING | awk '{print $NF}' | head -1)
  if [ -n "$pid" ]; then taskkill //PID "$pid" //F 2>/dev/null || true; fi
  if [ -n "$APP_PID" ]; then kill -9 "$APP_PID" 2>/dev/null || true; fi
  APP_PID=""
}
trap stop_app EXIT

if [ ! -f "$JAR" ]; then
  echo "[FAIL] 未找到 $JAR，先执行：mvn -s settings-openvpp.xml install -DskipTests"
  exit 1
fi

step "0. 启动容器中间件：docker compose up -d mysql redis"
docker compose up -d mysql redis

step "0.1 启动应用（docker profile，连容器 MySQL/Redis）"
start_app
wait_status UP 90
echo "health=$(curl -fsS "$API/system/health")"

step "1. 基线：run 键A=$KEY_A 闭环（Redis 可用）"
run_a=$(curl -fsS -X POST "$API/demo/run?responseId=$KEY_A&path=NORMAL&declaredKwh=600&targetKw=900")
settle_a=$(echo "$run_a" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)
check "键A 净实收" "1200.00" "$settle_a"
redis_key=$(docker compose exec -T redis redis-cli --scan --pattern "openvpp:idempotency:run:$KEY_A" | head -1)
check "结果缓存已写 Redis（键存在）" "openvpp:idempotency:run:$KEY_A" "$redis_key"

step "2. 故障注入：docker compose stop redis"
docker compose stop redis
wait_status DEGRADED 30
echo "health=$(curl -fsS "$API/system/health")"

step "3. Redis 不可用下新业务（键B=$KEY_B）不 500、落库兜底"
run_b=$(curl -fsS -X POST "$API/demo/run?responseId=$KEY_B&path=NORMAL&declaredKwh=600&targetKw=900")
settle_b=$(echo "$run_b" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)
check "键B 净实收（数据库兜底路径）" "1200.00" "$settle_b"

step "4. Redis 不可用下同键重放（键A）不重复出账"
replay_a=$(curl -fsS -X POST "$API/demo/run?responseId=$KEY_A&path=NORMAL&declaredKwh=600&targetKw=900")
check "键A 重放标记" "true" "$(echo "$replay_a" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
check "键A 重放金额一致" "$settle_a" "$(echo "$replay_a" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)"
bills_a=$(curl -fsS "$API/bills?responseId=$KEY_A" | grep -io '"BILL_TYPE"' | wc -l || true)
check "键A 账单条数不变（V1 五条）" "5" "$bills_a"

step "5. 故障恢复：docker compose start redis"
docker compose start redis
wait_status UP 60
echo "health=$(curl -fsS "$API/system/health")"

step "6. 进程中断：kill -9 应用 → 重启 → 同键重放（幂等键与版本化仓库状态一致性）"
bills_before=$(curl -fsS "$API/bills?responseId=$KEY_A" | grep -io '"BILL_TYPE"' | wc -l || true)
stop_app
echo "进程已 kill -9，重启应用…"
start_app
wait_status UP 90
replay2=$(curl -fsS -X POST "$API/demo/run?responseId=$KEY_A&path=NORMAL&declaredKwh=600&targetKw=900")
check "重启后键A 重放标记" "true" "$(echo "$replay2" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
check "重启后键A 金额一致" "$settle_a" "$(echo "$replay2" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)"
bills_after=$(curl -fsS "$API/bills?responseId=$KEY_A" | grep -io '"BILL_TYPE"' | wc -l || true)
check "重启后键A 账单条数不增" "$bills_before" "$bills_after"

step "7. 重置后干净复跑：POST /demo/reset → 同键全新执行"
curl -fsS -X POST "$API/demo/reset" >/dev/null
rerun=$(curl -fsS -X POST "$API/demo/run?responseId=$KEY_A&path=NORMAL&declaredKwh=600&targetKw=900")
check "重置后键A 为全新执行" "false" "$(echo "$rerun" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)"
check "重置后键A 净实收" "1200.00" "$(echo "$rerun" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)"
curl -fsS -X POST "$API/demo/reset" >/dev/null
echo "[PASS] 演示数据已复位"

echo ""
echo "FAULT INJECTION OK：Redis 降级 / 进程中断恢复 / 重置复跑全部通过（KEY_A=$KEY_A KEY_B=$KEY_B）"

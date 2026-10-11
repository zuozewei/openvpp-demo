#!/usr/bin/env bash
# 演示重置脚本（专栏第 59 篇「部署与恢复」验收格：重置同时清理数据库与运行态）
# 用法：
#   ./reset-demo.sh              应用内重置（推荐）：POST /api/v1/demo/reset 一条命令同时清
#                                业务库表（dr_task/dispatch_instruction/baseline_record/bill/
#                                dispute_correction）与进程内运行态（容量预占台账 + 内存指令
#                                仓库 + Redis 幂等缓存命名空间），重置后可干净复跑
#   ./reset-demo.sh --direct     绕过应用直清容器数据（应急/应用不可用时）：MySQL truncate
#                                业务表 + Redis 删除 openvpp:idempotency:run:* 前缀键；
#                                进程内运行态随应用重启自然清零
# 环境变量：BASE（默认 http://127.0.0.1:8080）；--direct 模式下需在仓库根目录执行
#          （读取 docker-compose.yml 的 mysql/redis 服务）
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
MODE="app"
if [ "${1:-}" = "--direct" ]; then
  MODE="direct"
elif [ -n "${1:-}" ]; then
  BASE="$1"
fi
API="$BASE/api/v1"

if [ "$MODE" = "app" ]; then
  echo "== 应用内重置 POST $API/demo/reset（库表 + 内存运行态 + Redis 命名空间同清）"
  resp=$(curl -fsS -X POST "$API/demo/reset")
  echo "$resp"
else
  echo "== 直清容器数据（应用须已停止或重启，进程内运行态随重启清零）"
  docker compose exec -T mysql mysql -uroot -popenvpp-demo openvpp_demo -e \
    "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE dr_task; TRUNCATE TABLE dispatch_instruction; TRUNCATE TABLE baseline_record; TRUNCATE TABLE bill; TRUNCATE TABLE dispute_correction; SET FOREIGN_KEY_CHECKS=1;"
  echo "MySQL 业务表已清空（sys_account 演示账号与 operation_audit 审计留痕保留）"
  docker compose exec -T redis sh -c \
    'redis-cli --scan --pattern "openvpp:idempotency:run:*" | xargs -r redis-cli del'
  echo "Redis 幂等缓存命名空间已清空"
fi

echo "== 重置验证：任务/账单应清零，健康检查应 UP，演示账号应保持"
tasks=$(curl -fsS "$API/tasks" | grep -io '"RESPONSE_ID"' | wc -l || true)
bills=$(curl -fsS "$API/bills" | grep -io '"BILL_TYPE"' | wc -l || true)
health=$(curl -fsS "$API/system/health")
overall=$(echo "$health" | grep -io '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)
accounts=$(echo "$health" | grep -io '"demoAccounts":[0-9]*' | cut -d: -f2)

fail=0
[ "$tasks" = "0" ] && echo "[PASS] 任务表已清零" || { echo "[FAIL] 任务表残留 $tasks 行"; fail=1; }
[ "$bills" = "0" ] && echo "[PASS] 账单表已清零" || { echo "[FAIL] 账单表残留 $bills 行"; fail=1; }
[ "$overall" = "UP" ] && echo "[PASS] 健康检查 UP" || { echo "[FAIL] 健康检查=$overall"; fail=1; }
[ "$accounts" = "3" ] && echo "[PASS] 演示账号保持 3 个（种入物不受重置影响）" || { echo "[FAIL] 演示账号=$accounts"; fail=1; }

[ "$fail" = "0" ] && echo "RESET OK" || exit 1

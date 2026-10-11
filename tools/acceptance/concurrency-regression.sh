#!/usr/bin/env bash
# 关键并发用例回归复跑（专栏第 59 篇验收配套：并发回归）
# 用法：./concurrency-regression.sh
# 覆盖（按验收格逐项对应）：
#   申报并发占用守恒      → openvpp-market ChargeDeclarationConcurrencyTest
#                            （十线程并发申报同场站同事件占用守恒 + 同请求标识仅受理一次）
#   结算并发确认幂等      → openvpp-settlement SettlementServiceTest#并发确认幂等_首条留痕为准
#   贯穿案例并发幂等/版本 → openvpp-app ParkResponseConcurrencyTest
#                            （50 路并发唯一执行 / 12 路并发纠偏版本连续 / 同键并发不重复出账）
#   真实容器并发          → openvpp-app MySqlComposeIT（需 docker compose 的 MySQL+Redis 可达，
#                            不可达自动跳过并写明原因，与既有门控口径一致）
set -euo pipefail

cd "$(dirname "$0")/../.."   # 仓库根目录
MVN="mvn -s settings-openvpp.xml"
fail=0

run_suite() { # $1 说明  $2... 命令
  local desc="$1"; shift
  echo ""
  echo "== $desc"
  echo "命令: $*"
  if "$@"; then
    echo "[PASS] $desc"
  else
    echo "[FAIL] $desc"
    fail=1
  fi
}

run_suite "申报并发占用守恒（ChargeDeclarationConcurrencyTest）" \
  $MVN -pl openvpp-market test -Dtest=ChargeDeclarationConcurrencyTest

run_suite "结算并发确认幂等（SettlementServiceTest#并发确认幂等_首条留痕为准）" \
  $MVN -pl openvpp-settlement test "-Dtest=SettlementServiceTest#并发确认幂等_首条留痕为准"

run_suite "贯穿案例并发幂等与版本化（ParkResponseConcurrencyTest）" \
  $MVN -pl openvpp-app test -Dtest=ParkResponseConcurrencyTest

echo ""
echo "== 真实容器并发（MySqlComposeIT，需 MySQL 127.0.0.1:3306 + Redis 127.0.0.1:6379）"
mysql_ok=0; redis_ok=0
if (exec 3<>/dev/tcp/127.0.0.1/3306) 2>/dev/null; then mysql_ok=1; fi
if (exec 3<>/dev/tcp/127.0.0.1/6379) 2>/dev/null; then redis_ok=1; fi

if [ "$mysql_ok" = "1" ] && [ "$redis_ok" = "1" ]; then
  echo ""
  echo "== 真实容器并发（MySqlComposeIT）"
  echo "命令: $MVN -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false"
  # 端口通≠口令符：IT 内部按 root/openvpp-demo 试连，不符时整类门控跳过（mvn 仍退出 0），
  # 须读 surefire 摘要区分「真跑通过」与「门控跳过」；不用 -q（-q 抑制 surefire 摘要）
  it_out=$($MVN -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false 2>&1 | grep -E "Tests run:" | tail -2 || true)
  echo "$it_out"
  if echo "$it_out" | grep -q "Skipped: 4"; then
    echo "[SKIP] MySqlComposeIT 按门控跳过（127.0.0.1:3306 口令不符或不可达）：本格标未覆盖，不计失败"
  elif echo "$it_out" | grep -q "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0"; then
    echo "[PASS] 真实容器并发（MySqlComposeIT）"
  else
    echo "[FAIL] 真实容器并发（MySqlComposeIT）"
    fail=1
  fi
else
  echo "[SKIP] 真实 MySQL/Redis 不可达（mysql_ok=$mysql_ok redis_ok=$redis_ok）：" \
       "先 docker compose up -d mysql redis；本格以跳过留痕，不计入失败"
fi

echo ""
if [ "$fail" = "0" ]; then
  echo "CONCURRENCY REGRESSION OK"
else
  echo "CONCURRENCY REGRESSION FAILED"
  exit 1
fi

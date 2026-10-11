#!/usr/bin/env bash
# 演示链路冒烟脚本（专栏第 59 篇验收配套：干净环境复跑与逐格证据的固定动作）
# 用法：./smoke-demo.sh [base_url]
#   base_url 默认 http://127.0.0.1:8080
# 步骤：health 健康检查 → demo/run 闭环 → bills 金额核对 → 同键幂等重放 →
#       dispute 争议更正 → demo/reset 重置后同键复跑
# 退出码：任一步骤断言失败即非零退出，便于验收记录「原样命令 + 实际输出」
set -euo pipefail

BASE="${1:-http://127.0.0.1:8080}"
API="$BASE/api/v1"
RID="smoke-$(date +%H%M%S)"

step() { echo ""; echo "== $1"; }
check() { # $1 断言名  $2 预期  $3 实际
  if [ "$2" = "$3" ]; then
    echo "[PASS] $1"
  else
    echo "[FAIL] $1  预期=$2  实际=$3"
    exit 1
  fi
}

step "1. 健康检查 GET $API/system/health"
health=$(curl -fsS "$API/system/health")
echo "$health"
overall=$(echo "$health" | grep -io '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)
check "整体状态" "UP" "$overall"
db=$(echo "$health" | grep -io '"db":{[^}]*"status":"[A-Z]*"' | grep -io '"status":"[A-Z]*"' | cut -d'"' -f4)
check "数据源组件" "UP" "$db"

step "2. 触发闭环 POST $API/demo/run?responseId=$RID"
run=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
echo "$run" | grep -io '"trace":\[.*' >/dev/null && echo "(trace 步骤日志略，共 $(echo "$run" | grep -io '①\|②\|③\|④\|⑤\|⑥\|⑦\|⑧\|⑨\|⑩\|⑪' | wc -l) 个步骤标记)"
settle=$(echo "$run" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)
check "默认案例净实收" "1200.00" "$settle"
replay=$(echo "$run" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)
check "首次执行为非重放" "false" "$replay"

step "3. 账单核对 GET $API/bills?responseId=$RID"
bills=$(curl -fsS "$API/bills?responseId=$RID")
echo "$bills"
bill_count=$(echo "$bills" | grep -io '"BILL_TYPE"' | wc -l || true)
check "V1 账单条数（SETTLE+PLATFORM_CUT+SHARE×3）" "5" "$bill_count"

step "4. 同键幂等重放 POST $API/demo/run?responseId=$RID（不重复出账）"
rerun=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
replay=$(echo "$rerun" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)
check "同键重放标记" "true" "$replay"
settle2=$(echo "$rerun" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)
check "重放金额一致" "1200.00" "$settle2"
tasks=$(curl -fsS "$API/tasks" | grep -io "\"RESPONSE_ID\":\"$RID\"" | wc -l || true)
check "任务表唯一行" "1" "$tasks"

step "5. 争议更正 POST $API/demo/dispute?responseId=$RID"
dispute=$(curl -fsS -X POST "$API/demo/dispute?responseId=$RID&correctedActualKw=380&correctionRequestId=req-$RID")
version=$(echo "$dispute" | grep -io '"correctionVersion":"[^"]*"' | cut -d'"' -f4)
check "更正版本" "V2" "$version"
diff=$(echo "$dispute" | grep -io '"correctionDiffYuan":[0-9.-]*' | cut -d: -f2)
check "默认案例更正差额（封顶口径为 0）" "0.00" "$diff"

step "6. 重置后同键复跑 POST $API/demo/reset → demo/run?responseId=$RID"
cleared=$(curl -fsS -X POST "$API/demo/reset" | grep -io '"cleared":[0-9]*' | cut -d: -f2)
echo "reset cleared=$cleared 行"
after_reset=$(curl -fsS -X POST "$API/demo/run?responseId=$RID&path=NORMAL&declaredKwh=600&targetKw=900")
replay3=$(echo "$after_reset" | grep -io '"idempotentReplay":[a-z]*' | cut -d: -f2)
check "重置后同键为全新执行" "false" "$replay3"
settle3=$(echo "$after_reset" | grep -io '"settleYuan":[0-9.]*' | cut -d: -f2)
check "复跑净实收" "1200.00" "$settle3"

step "7. 复位演示数据（保留运行后干净态）"
curl -fsS -X POST "$API/demo/reset" >/dev/null
echo "[PASS] 复位完成"

echo ""
echo "SMOKE OK: $RID 全步骤通过（base=$BASE）"

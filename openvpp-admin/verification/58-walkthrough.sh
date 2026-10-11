#!/usr/bin/env bash
# openvpp-admin 第 58 篇全链路联调实录：3 演示账号 × 运营全接口
# 后端：openvpp-app @ b5d2d8f（8080）；前端代理 5174 → 8080
set -u
BASE=${BASE:-http://localhost:8081/api/v1/operations}
OUT=$(cd "$(dirname "$0")" && pwd)/curl-transcript.txt
: > "$OUT"

log() { echo "$@" >> "$OUT"; }
req() {
  local label="$1"; shift
  {
    echo ""
    echo "### $label"
    echo "curl $*"
  } >> "$OUT"
  local body
  body=$(curl -s -w $'\nHTTP_STATUS:%{http_code}' "$@")
  local status
  status=$(echo "$body" | tail -1 | cut -d: -f2)
  local payload
  payload=$(echo "$body" | sed '$d')
  echo "HTTP $status" >> "$OUT"
  echo "$payload" | jq -c '.' 2>/dev/null | head -40 >> "$OUT" || echo "$payload" | head -40 >> "$OUT"
  echo "$payload"
}

login() {
  local account="$1"
  req "登录 $account" -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
    -d "{\"account\":\"$account\",\"password\":\"Openvpp@2026\"}" | jq -r '.token'
}

log "==== openvpp-admin 联调实录 $(date '+%F %T') ===="
log "后端 BASE=$BASE（openvpp-app @ b5d2d8f）"

# ---------------- 账号与边界 ----------------
PT=$(login platform_admin)
OT=$(login operator_admin)
ST=$(login station_admin)
log ""
log "### 错误口令登录（期望 401）"
req "错误口令" -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  -d '{"account":"platform_admin","password":"wrong"}' > /dev/null

req "GET /auth/me（平台）" "$BASE/auth/me" -H "Authorization: Bearer $PT" > /dev/null
req "GET /auth/me（运营商）" "$BASE/auth/me" -H "Authorization: Bearer $OT" > /dev/null
req "GET /auth/me（场站）" "$BASE/auth/me" -H "Authorization: Bearer $ST" > /dev/null

log ""
log "### 越角色写操作（期望 401：服务端授权才是边界）"
req "运营商建事件（应 401）" -X POST "$BASE/events" -H "Authorization: Bearer $OT" \
  -H "Content-Type: application/json" -d '{"direction":"PEAK_SHAVE"}' > /dev/null
req "场站确认申报越权探测（应 404/401）" -X POST "$BASE/declarations/NONE/confirm" \
  -H "Authorization: Bearer $ST" > /dev/null
req "场站查审计（应 401）" "$BASE/audit-logs" -H "Authorization: Bearer $ST" > /dev/null

# ---------------- 平台：建事件 → 发布 → 授权 ----------------
NOW=$(date +%s)
DEADLINE=$(date -d "@$((NOW+150))" '+%Y-%m-%dT%H:%M:%S')
WSTART=$(date -d "@$((NOW+180))" '+%Y-%m-%dT%H:%M:%S')
WEND=$(date -d "@$((NOW+300))" '+%Y-%m-%dT%H:%M:%S')
EVT="EVT-UI58-$(date +%H%M%S)"

req "平台建事件 $EVT" -X POST "$BASE/events" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"direction\":\"PEAK_SHAVE\",\"windowStart\":\"$WSTART\",\"windowEnd\":\"$WEND\",\"targetAdjustKw\":500,\"declareDeadline\":\"$DEADLINE\"}" > /dev/null

req "平台发布事件" -X POST "$BASE/events/$EVT/publish" -H "Authorization: Bearer $PT" > /dev/null
req "平台参与授权（两个演示场站）" -X POST "$BASE/events/$EVT/participations" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"stationIds":["cs-station-01","cs-station-02"]}' > /dev/null

req "场站视角事件清单（仅已发布且授权覆盖）" "$BASE/events" -H "Authorization: Bearer $ST" > /dev/null
req "运营商视角事件清单" "$BASE/events" -H "Authorization: Bearer $OT" > /dev/null

# ---------------- 场站申报（含拒绝场景） ----------------
REQ1="REQ-UI58-$(date +%s)-a"
req "场站申报成功（cs-station-01 / 500kW / demo-v1，申报=目标 → 缺口 0）" -X POST "$BASE/declarations" \
  -H "Authorization: Bearer $ST" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"stationId\":\"cs-station-01\",\"declaredKw\":500,\"snapshotVersion\":\"demo-v1\",\"requestId\":\"$REQ1\"}" > /dev/null

req "重复申报（同 requestId 幂等，返回原单）" -X POST "$BASE/declarations" \
  -H "Authorization: Bearer $ST" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"stationId\":\"cs-station-01\",\"declaredKw\":500,\"snapshotVersion\":\"demo-v1\",\"requestId\":\"$REQ1\"}" > /dev/null

req "场站超额申报（应 409 容量守门）" -X POST "$BASE/declarations" \
  -H "Authorization: Bearer $ST" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"stationId\":\"cs-station-01\",\"declaredKw\":999,\"snapshotVersion\":\"demo-v1\",\"requestId\":\"REQ-UI58-over\"}" > /dev/null

req "场站申报未授权场站（应 409）" -X POST "$BASE/declarations" \
  -H "Authorization: Bearer $ST" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"stationId\":\"cs-station-02\",\"declaredKw\":100,\"snapshotVersion\":\"demo-v1\",\"requestId\":\"REQ-UI58-s02\"}" > /dev/null

req "快照失效版本申报（应 400：未登记快照为入参错误）" -X POST "$BASE/declarations" \
  -H "Authorization: Bearer $ST" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\",\"stationId\":\"cs-station-01\",\"declaredKw\":100,\"snapshotVersion\":\"demo-v0\",\"requestId\":\"REQ-UI58-v0\"}" > /dev/null

# ---------------- 运营商确认申报 ----------------
DECL=$(req "申报清单（运营商视角）" "$BASE/declarations" -H "Authorization: Bearer $OT")
DECL_ID=$(echo "$DECL" | jq -r '.[] | select(.eventId=="'$EVT'" and .status=="SUBMITTED") | .declarationId')
req "运营商确认申报 $DECL_ID" -X POST "$BASE/declarations/$DECL_ID/confirm" \
  -H "Authorization: Bearer $OT" > /dev/null

# ---------------- 等申报截止 → 平台构建计划 → 运营商确认/下发 ----------------
log ""
log "### 等待申报截止（截止守门：派单时刻不得早于申报截止）"
sleep 155
req "平台构建计划（事件 $EVT）" -X POST "$BASE/plans" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" -d "{\"eventId\":\"$EVT\"}" > /dev/null

PLAN=$(req "计划清单（事件过滤）" "$BASE/plans?eventId=$EVT" -H "Authorization: Bearer $OT")
PLAN_ID=$(echo "$PLAN" | jq -r '.[0].planId')
req "计划详情（分配行/子计划/版本历史）" "$BASE/plans/$PLAN_ID" -H "Authorization: Bearer $OT" > /dev/null
req "运营商确认计划" -X POST "$BASE/plans/$PLAN_ID/confirm" -H "Authorization: Bearer $OT" > /dev/null
req "重复确认（幂等，不重复留痕）" -X POST "$BASE/plans/$PLAN_ID/confirm" -H "Authorization: Bearer $OT" > /dev/null
req "运营商下发计划" -X POST "$BASE/plans/$PLAN_ID/send" -H "Authorization: Bearer $OT" > /dev/null
req "重复下发（幂等拦截）" -X POST "$BASE/plans/$PLAN_ID/send" -H "Authorization: Bearer $OT" > /dev/null
req "指令证据查询（planId 过滤）" "$BASE/commands?planId=$PLAN_ID" -H "Authorization: Bearer $OT" > /dev/null

# ---------------- 监测演示编排 + 告警确认 ----------------
LRESULT=$(req "平台监测演示编排（LATE_STREAM 迟到场景：曲线质量标记/证据资格）" -X POST "$BASE/monitoring/demo-runs" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"scenario":"LATE_STREAM","direction":"PEAK_SHAVE"}')
req "迟到事件执行评估查询" "$BASE/monitoring/executions?eventId=$(echo "$LRESULT" | jq -r '.eventId')" -H "Authorization: Bearer $OT" > /dev/null

MRESULT=$(req "平台监测演示编排（PEAK_BIAS 方向超差场景：产生告警）" -X POST "$BASE/monitoring/demo-runs" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"scenario":"PEAK_BIAS","direction":"PEAK_SHAVE"}')
MEVT=$(echo "$MRESULT" | jq -r '.eventId')
req "执行评估查询（超差事件）" "$BASE/monitoring/executions?eventId=$MEVT" -H "Authorization: Bearer $OT" > /dev/null
ALERTS=$(req "告警查询（超差事件）" "$BASE/monitoring/alerts?eventId=$MEVT" -H "Authorization: Bearer $OT")
ALERT_ID=$(echo "$ALERTS" | jq -r '.[0].alertId')
req "运营商确认告警 $ALERT_ID" -X POST "$BASE/monitoring/alerts/$ALERT_ID/confirm" \
  -H "Authorization: Bearer $OT" > /dev/null

ORESULT=$(req "平台监测演示编排（OFFLINE 失联场景）" -X POST "$BASE/monitoring/demo-runs" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"scenario":"OFFLINE","direction":"PEAK_SHAVE"}')
OEVT=$(echo "$ORESULT" | jq -r '.eventId')
req "失联事件指令证据（NO_RECEIPT 进核查）" "$BASE/commands?planId=$(echo "$ORESULT" | jq -r '.planId')" \
  -H "Authorization: Bearer $PT" > /dev/null

# ---------------- 评估演示编排 + 申请/复核 ----------------
ARESULT=$(req "平台评估演示编排（MISSING_POINTS 缺口场景）" -X POST "$BASE/assessments/demo-runs" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"scenario":"MISSING_POINTS","direction":"PEAK_SHAVE"}')
AEVT=$(echo "$ARESULT" | jq -r '.eventId')
req "运营商对已结束事件申请评估（幂等返回原记录）" -X POST "$BASE/assessments" \
  -H "Authorization: Bearer $OT" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$AEVT\"}" > /dev/null
ASSESS=$(req "评估记录查询" "$BASE/assessments?eventId=$AEVT" -H "Authorization: Bearer $PT")
REVIEW_ID=$(echo "$ASSESS" | jq -r '.[] | select(.conclusion=="MANUAL_REVIEW") | .assessmentId' | head -1)
req "平台复核留痕（MANUAL_REVIEW 记录）" -X POST "$BASE/assessments/$REVIEW_ID/review" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"note":"gap-section verified, keep MANUAL_REVIEW (ui58 demo)"}' > /dev/null

HRESULT=$(req "平台评估演示编排（HEALTHY 核定通过场景）" -X POST "$BASE/assessments/demo-runs" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d '{"scenario":"HEALTHY","direction":"PEAK_SHAVE"}')
HEVT=$(echo "$HRESULT" | jq -r '.eventId')
HASSESS=$(req "HEALTHY 事件评估记录" "$BASE/assessments?eventId=$HEVT" -H "Authorization: Bearer $PT")
OK_ASSESS=$(echo "$HASSESS" | jq -r '.[] | select(.conclusion=="CONFIRMED") | .assessmentId' | head -1)

req "对未结束事件申请评估（应 409）" -X POST "$BASE/assessments" \
  -H "Authorization: Bearer $OT" -H "Content-Type: application/json" \
  -d "{\"eventId\":\"$EVT\"}" > /dev/null

# ---------------- 结算：生成/确认/模拟付款/更正 + 拒绝场景 ----------------
SREQ="REQ-UI58-settle-$(date +%s)"
SRESULT=$(req "平台生成结算单（核定通过评估，扣款 0：演示窗短、毛额亚元级）" -X POST "$BASE/settlements" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d "{\"assessmentId\":\"$OK_ASSESS\",\"priceYuanPerKwh\":1.2,\"deductionYuan\":0,\"rule\":{\"platformRate\":0.1,\"operatorRate\":0.3,\"stationRate\":0.6},\"requestId\":\"$SREQ\"}")
SET_ID=$(echo "$SRESULT" | jq -r '.settlementId')

req "重复生成（同 requestId 幂等，返回原单）" -X POST "$BASE/settlements" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d "{\"assessmentId\":\"$OK_ASSESS\",\"priceYuanPerKwh\":1.2,\"deductionYuan\":0,\"rule\":{\"platformRate\":0.1,\"operatorRate\":0.3,\"stationRate\":0.6},\"requestId\":\"$SREQ\"}" > /dev/null

req "分账比例和≠1（应 400）" -X POST "$BASE/settlements" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" \
  -d "{\"assessmentId\":\"$OK_ASSESS\",\"priceYuanPerKwh\":1.2,\"rule\":{\"platformRate\":0.2,\"operatorRate\":0.3,\"stationRate\":0.6},\"requestId\":\"REQ-UI58-rate\"}" > /dev/null

req "对转复核评估生成结算（应 409）" -X POST "$BASE/settlements" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" \
  -d "{\"assessmentId\":\"$REVIEW_ID\",\"priceYuanPerKwh\":1.2,\"rule\":{\"platformRate\":0.1,\"operatorRate\":0.3,\"stationRate\":0.6},\"requestId\":\"REQ-UI58-mr\"}" > /dev/null

req "考核扣款超毛额（应 409）" -X POST "$BASE/settlements" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" \
  -d "{\"assessmentId\":\"$OK_ASSESS\",\"priceYuanPerKwh\":1.2,\"deductionYuan\":999,\"rule\":{\"platformRate\":0.1,\"operatorRate\":0.3,\"stationRate\":0.6},\"requestId\":\"REQ-UI58-ded\"}" > /dev/null

req "平台确认结算单（v1）" -X POST "$BASE/settlements/$SET_ID/confirm" -H "Authorization: Bearer $PT" > /dev/null
req "更正结算单（核定电量下调 → 新账单版本 v2，历史版本留存）" -X POST "$BASE/settlements/$SET_ID/corrections" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d "{\"correctionRequestId\":\"CORR-UI58-$(date +%s)\",\"confirmedResponseKwh\":0.8,\"deductionYuan\":0,\"reason\":\"confirmed-kwh adjusted after recheck (ui58 demo correction chain)\"}" > /dev/null
req "更正后确认 v2" -X POST "$BASE/settlements/$SET_ID/confirm" -H "Authorization: Bearer $PT" > /dev/null
req "更正后再次更正（付款登记前合法，生成 v3）" -X POST "$BASE/settlements/$SET_ID/corrections" \
  -H "Authorization: Bearer $PT" -H "Content-Type: application/json" \
  -d "{\"correctionRequestId\":\"CORR-UI58-late-$(date +%s)\",\"confirmedResponseKwh\":0.7,\"deductionYuan\":0,\"reason\":\"late correction before payment (ui58 demo)\"}" > /dev/null
# 说明：v3 生成后处于已生成未确认状态，下一步付款登记被守门拦下（未确认拒付款 409）；
# 「已登记付款的单据禁止更正」由服务层单测与第 57 篇 HTTP 实录覆盖，本脚本不再重复演示。
req "未确认版本拒绝付款登记（应 409，状态机守门）" -X POST "$BASE/settlements/$SET_ID/payments" -H "Authorization: Bearer $PT" \
  -H "Content-Type: application/json" -d "{\"paymentRequestId\":\"PAY-UI58-$(date +%s)\"}" > /dev/null
req "结算单详情（历史版本/更正链/模拟付款记录）" "$BASE/settlements/$SET_ID" \
  -H "Authorization: Bearer $PT" > /dev/null
req "结算清单（场站视角，仅本场站单据）" "$BASE/settlements" -H "Authorization: Bearer $ST" > /dev/null

# ---------------- 审计 ----------------
req "平台审计日志" "$BASE/audit-logs" -H "Authorization: Bearer $PT" > /dev/null

log ""
log "==== 实录结束 ===="

# 教程 02：园区需求响应贯穿案例（第 19 篇）

> 目标：以一次园区需求响应为主线，跑通 11 个模块串联的完整业务闭环，
> 并验证幂等、并发认领、争议更正等工程口径。

## 前置条件

- 完成[教程 01](01-quick-start.md)（JDK 11 + Maven）。
- 无任何外部中间件依赖：H2 文件库 + 网关本地模拟模式，断网可跑。

## 链路与数据口径（请务必先读）

`openvpp-app` 把各业务模块串成完整闭环（H2 文件库持久化，读者零外部依赖）：

```
模拟遥测 → 接入校验 → 数据入库 → 能力评估 → 资源聚合 → 响应任务
        → 指令下发 → 执行核验 → 响应量计算 → 结算分摊 → 账单查询
```

- 园区案例为**模拟数据驱动的业务编排**：遥测/计量/资源容量均为编排层内置模拟源
  （`buildSamples`/`buildMembers` 直接构造），**不来自网关真实协议接入**；
  链路其余环节（评估/聚合/指令/结算）走真实业务代码。
- 结算采用四量口径：补偿毛额 → 考核扣款（落账 `PENALTY`，从应收补贴中扣除）
  → 平台净实收（落账 `SETTLE`，即可分配金额）→ 分摊。
- 教学假设数值与逐场景验证记录见 [../case-study/park-demo.md](../case-study/park-demo.md) 手工核算底稿。

## 操作步骤

```bash
# 1. 打包并启动
mvn -s settings-openvpp.xml -pl openvpp-app -am -DskipTests package
java -jar openvpp-app/target/openvpp-app-1.0.0.jar

# 2. 四条路径
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 正常：600kWh/毛额1200/净实收1200元
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-002&path=DEGRADED"  # 降级：缺口 148.5kW
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 幂等重放：idempotentReplay=true
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-pen&path=NORMAL&declaredKwh=800"  # 非零考核：考核400元、净实收800元

# 3. 争议更正（结算后独立入口；正/负差额与多轮更正均支持，历史版本保留）
# correctionRequestId 为纠偏请求幂等键（可选，4-64 位）：同键重复提交返回原版本结果不重复出账
curl -X POST "http://localhost:8080/api/v1/demo/dispute?responseId=run-pen&correctedActualKw=380&correctionRequestId=req-001"

# 4. 查询入口（任务/指令/基线/账单，responseId 贯穿关联；账单含 BILL_VERSION 列，V1/V2 并存）
curl "http://localhost:8080/api/v1/tasks"
curl "http://localhost:8080/api/v1/instructions?responseId=run-001"
curl "http://localhost:8080/api/v1/baselines?responseId=run-001"
curl "http://localhost:8080/api/v1/bills?responseId=run-001"

# 5. 重置（清理演示数据后可再跑）
curl -X POST "http://localhost:8080/api/v1/demo/reset"
```

## 验证方法

| 场景 | 预期结果 |
|------|----------|
| NORMAL（申报 600） | 净实收 1200 元；分摊 475/380/285；服务费 60；资金守恒 |
| DEGRADED | 充电桩掉线后可承诺 751.5 kW < 900 kW → 缺口 148.5 kW，不出账 |
| 幂等重放 | 同一 `responseId` 再发，`idempotentReplay=true`，不重复下发/出账 |
| 非零考核（申报 800） | 考核 400 元落账 `PENALTY`；净实收 800 元 |
| 争议更正 | 生成 V2 版更正账单五件套（SETTLE/PENALTY/PLATFORM_CUT/SHARE/CORRECTION），V1 保留 |

## 工程口径速览

- **幂等保证**：同一 `responseId` 重复触发不重复下发、不重复出账（唯一例外：任务已结算后再以
  `path=DISPUTED` 触发 = 争议更正请求，不被幂等拦截）；`responseId` 限 4-50 位
  （派生指令编号须落在 `VARCHAR(64)` 内，超长前置 400 拒绝）。
- **并发认领**：`run` 入口以事务内 `INSERT`（`response_id` 唯一主键即认领锁）原子认领任务——
  同键并发后到者在唯一索引上阻塞，持有方提交后其转幂等重放（实测 50 路并发恰好 1 路完整执行）；
  持有方回滚则后到者自动接管。
- **争议更正**：以 `correctionRequestId` 拦截同键重复提交，`SELECT .. FOR UPDATE` 串行化版本分配，
  更正账单一律 `INSERT`（禁止覆盖历史版本）——实测 12 路并发不同请求生成 12 个连续版本互不覆盖。
- **重启持久化**：任务与账单在重启后仍在（H2 文件库 `~/.openvpp/openvpp-db`）。
- **Redis 缓存（可选）**：单体形态默认不启用；Docker 形态下见[教程 03](03-docker-compose.md)。
  正确性始终由数据库唯一约束 + 事务内原子认领兜底。

> 升级说明：若存在旧版本演示库文件，请先删除 `~/.openvpp/openvpp-db*` 再启动（教学库不做迁移）。

## 常见问题

- **想观察 50 路并发只有 1 路执行**：对同一 `responseId` 并发发 50 个 `run` 请求（如 `xargs -P 50`），
  返回中恰好一路为完整执行、其余 `idempotentReplay=true`。
- **响应 400 提示 responseId 非法**：检查长度 4-50 位与字符集。
- **想清空重来**：`POST /api/v1/demo/reset`，或停服后删除 `~/.openvpp/openvpp-db*`。

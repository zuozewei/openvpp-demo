# openvpp-demo

> 专栏《虚拟电厂系统开发实战：从物联接入到市场化运营》配套示例工程
> 定位：最小可运行实现，不是玩具 Demo，也不是生产代码
> 技术栈：Java 11 · Spring Boot 2.7 · Maven 多模块

## 模块总览

| 模块 | 职责 | 对应专栏篇目 |
|------|------|--------------|
| `openvpp-common` | 统一返回、枚举常量（资源类型/场景），零业务依赖 | 第 01-04 篇 |
| `openvpp-resource` | 资源档案、物模型、设备影子、台账 | 第 04、07、11 篇 |
| `openvpp-assessment` | 能力评估算法（44260 七指标）、评估策略、GBDT 预测训推链 | 第 02、12、13 篇；交付栏第 47 篇 |
| `openvpp-aggregator` | VPP 单元、聚合引擎、准入门槛（47241 四指标） | 第 03、14 篇 |
| `openvpp-gateway` | MQTT/CoAP 协议接入 | 第 06 篇 |
| `openvpp-iot` | 设备认证、断网续传、多协议接入地图 | 第 09、10 篇；交付栏第 48 篇 |
| `openvpp-dispatch` | 指令链路、策略引擎、MPC、目标分解与调度闭环 | 第 15、16 篇；算法栏第 33 篇；交付栏第 46 篇 |
| `openvpp-settlement` | 基线核算、结算分摊 | 第 17、21 篇 |
| `openvpp-market` | 申报、竞价（简化演示） | 第 19、20 篇 |
| `openvpp-edge` | 边缘侧缓存补传 demo | 第 09 篇 |
| `openvpp-app` | 单体启动入口 + 业务闭环编排（贯穿案例） | 第 05、19、25 篇 |

## 快速开始

```bash
mvn -s settings-openvpp.xml install -DskipTests
cd openvpp-app && mvn -s ../settings-openvpp.xml spring-boot:run

# 验证
curl http://127.0.0.1:8080/api/v1/system/ping
# {"code":0,"message":"success","data":{"service":"openvpp-demo","status":"UP",...}}
```

> `settings-openvpp.xml`：全局 Maven 配置了不可达私服镜像时的逃生通道（显式走公共镜像，不动全局配置）。

## 园区需求响应贯穿案例（第 19 篇，已接入主工程）

`openvpp-app` 把各业务模块串成完整闭环（H2 文件库持久化，读者零外部依赖）：

```
模拟遥测 → 接入校验 → 数据入库 → 能力评估 → 资源聚合 → 响应任务
        → 指令下发 → 执行核验 → 响应量计算 → 结算分摊 → 账单查询
```

**数据与接入口径（请务必先读）**：

- 园区案例为**模拟数据驱动的业务编排**：遥测/计量/资源容量均为编排层内置模拟源（`buildSamples`/`buildMembers` 直接构造），**不来自网关真实协议接入**；链路其余环节（评估/聚合/指令/结算）走真实业务代码。
- 网关默认**本地模拟模式**（`openvpp.gateway.mode=local`）：`java -jar` 启动不连接任何外部消息服务，断网可跑；真实 MQTT/CoAP 协议接入须显式设置 `openvpp.gateway.mode=remote` 并配置 `openvpp.mqtt.broker`（工程不提供任何默认外部地址）。
- 三路径现状：`NORMAL`（正常）、`DEGRADED`（降级报缺口）、`DISPUTED`（争议计量补正）均已实现；另有结算后争议更正的**独立入口** `POST /api/v1/demo/dispute`。结算采用四量口径：补偿毛额 → 考核扣款（落账 `PENALTY`，从应收补贴中扣除）→ 平台净实收（落账 `SETTLE`，即可分配金额）→ 分摊；争议更正按更正计量重算四量，非零差额**全额传导**为下一账期版本（`bill_version`）的服务费与分摊重算，五件套更正账单（SETTLE/PENALTY/PLATFORM_CUT/SHARE/CORRECTION）独立留档，原始账单（V1）永不删除，支持同一任务多轮更正（口径与示例数值见 `openvpp-app/PARK-DEMO.md`）。
- 算法番外（第 33-35 篇）已**纳入代码工程并配模块级单测**（`openvpp-dispatch` 的 MPC 调度、`openvpp-settlement` 的区域结算等）；但主应用编排当前只调用**评估 → 聚合 → 指令 → 结算**主线，MPC 与区域结算模块**尚未接入编排链路**。
- 交付栏实战番外（第 46-48 篇）配套代码同样为**纳入主代码工程、未接编排链路**的模块级实现：`openvpp-dispatch` 的目标分解与评估闭环（`com.openvpp.dispatch.decompose` / `evalloop`，16 项单测）、`openvpp-assessment` 的 GBDT 训推链（`com.openvpp.assessment.predict`，10 项单测，Python 零依赖演示 `tools/ai/gbdt_demo.py`，`--selfcheck` 可自检跨语言公式契约）、`openvpp-iot` 的多协议接入地图（`com.openvpp.iot.protocol`，8 项单测）。全仓回归 200 项 / 实际执行 198 项（2 项 live 默认跳过）；另有真实 MySQL + Redis 集成回归 `MySqlComposeIT`（4 项，需 compose 服务在位，见下节，不计入常规口径）。

```bash
mvn -s settings-openvpp.xml -pl openvpp-app -am -DskipTests package
java -jar openvpp-app/target/openvpp-app-1.0.0.jar

# 四条路径（教学假设数值见 openvpp-app/PARK-DEMO.md 手工核算底稿）
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 正常：600kWh/毛额1200/净实收1200元
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-002&path=DEGRADED"  # 降级：缺口 148.5kW
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 幂等重放：idempotentReplay=true
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-pen&path=NORMAL&declaredKwh=800"  # 非零考核：考核400元、净实收800元

# 争议更正（结算后独立入口；正/负差额与多轮更正均支持，历史版本保留）
# correctionRequestId 为纠偏请求幂等键（可选，4-64 位）：同键重复提交返回原版本结果不重复出账
curl -X POST "http://localhost:8080/api/v1/demo/dispute?responseId=run-pen&correctedActualKw=380&correctionRequestId=req-001"

# 查询入口（任务/指令/基线/账单，responseId 贯穿关联；账单含 BILL_VERSION 列，V1/V2 并存）
curl "http://localhost:8080/api/v1/tasks"
curl "http://localhost:8080/api/v1/instructions?responseId=run-001"
curl "http://localhost:8080/api/v1/baselines?responseId=run-001"
curl "http://localhost:8080/api/v1/bills?responseId=run-001"

# 重置（清理演示数据后可再跑）
curl -X POST "http://localhost:8080/api/v1/demo/reset"
```

幂等保证：同一 `responseId` 重复触发不重复下发、不重复出账（唯一例外：任务已结算后再以 `path=DISPUTED` 触发 = 争议更正请求，不被幂等拦截）；`responseId` 限 4-50 位（派生指令编号须落在 `VARCHAR(64)` 内，超长前置 400 拒绝）；闭环执行中落库失败会先做内存态补偿（释放容量预占、清除指令镜像）再随事务回滚抛出。重启后任务与账单仍在（H2 文件库 `~/.openvpp/openvpp-db`）。

**并发幂等口径（第 5 轮整改，正确性由数据库承担）**：`run` 入口以事务内 `INSERT`（`response_id` 唯一主键即认领锁）原子认领任务——同键并发后到者在唯一索引上阻塞，持有方提交后其收到重复键并读取终态转幂等重放（实测 50 路并发恰好 1 路完整执行、49 路重放、任务/指令/账单各一份）；持有方回滚则后到者自动接管，回滚不留残状态。争议更正以 `correctionRequestId`（dispute_correction 留档表唯一键）拦截同键重复提交，`SELECT .. FOR UPDATE` 锁任务行串行化版本分配，更正账单一律 `INSERT`（禁止 MERGE 覆盖历史版本）——实测 12 个并发不同请求生成 12 个连续版本（V2..V13）互不覆盖。容量预占台账全方法互斥（消灭并发遍历 CME）。

**Redis 缓存时序与故障口径（第 6 轮复审修复）**：结果缓存经事务同步在**数据库提交后**写入（提交失败只释放在途标记，绝不留下「缓存成功、数据库回滚」的脏结果）；GAP 结果不缓存、同键可重跑；演示重置同步 `SCAN` 清空幂等缓存命名空间（重置后同键重新落库）；Redis 不可用在**守卫内部**按退化语义消化——读取失败=未命中、登记失败=放行进入数据库认领、写/清失败=静默告警（连接/命令超时 2 秒兜底，不再向业务 500）。事务隔离显式 `READ_COMMITTED`：MySQL 默认 REPEATABLE READ 曾使锁下快照读拿到过期版本号（容器实测 12 路并发纠偏只出 3 版），H2 默认 READ_COMMITTED 故教学库未暴露。

> 升级说明：`dispute_correction`（纠偏请求留档表）为新增结构。若存在旧版本演示库文件，
> 请先删除 `~/.openvpp/openvpp-db*` 再启动（教学库不做迁移）。

## Docker Compose 一键交付（第 25 篇）

```bash
docker compose up --build
# 应用 http://localhost:8080（docker profile），EMQX 控制台 http://localhost:18083（admin/openvpp）
```

一条命令拉起**应用 + MySQL + Redis + EMQX** 四个容器，三个中间件都有真实业务落点（非摆设）：

| 中间件 | 业务落点 |
|--------|----------|
| MySQL | 任务/指令/基线/账单持久化（`application-docker.yml` 数据源；`schema.sql` 两库同构，仓库层按数据源 URL 自适应方言：H2 用 `MERGE INTO .. KEY`，MySQL 用 `INSERT .. ON DUPLICATE KEY UPDATE`） |
| Redis | 已完成结果的幂等快速重放（`IdempotencyGuard`，`openvpp.idempotency.redis-enabled=true` 启用；在途标记 TTL 120s、结果缓存 TTL 30min）。**正确性始终由数据库唯一约束 + 事务内原子认领兜底**，Redis 不可用时守卫内部自动退化为纯数据库路径（故障退化有单测覆盖） |
| EMQX | `openvpp.gateway.mode=remote` 的 MQTT 协议接入（订阅 `openvpp/+/telemetry|event|ack`，教学消费者打 `[INGRESS-MOCK]` 日志） |

依赖就绪采用 `depends_on` + `service_healthy` 健康检查（mysqladmin / redis-cli / emqx ctl），应用不再"起了但连不上"。业务闭环的遥测/计量仍为编排层内置模拟源（口径见上节），与单体 jar 形态一致。

**真实环境回归（MySqlComposeIT，4 项）**：`docker compose up -d mysql redis` 后显式运行
`mvn -s settings-openvpp.xml -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false`，
覆盖真实 MySQL 建表、提交后 Redis 缓存写入、同键 12 路并发认领与并发纠偏版本连续性、
重置清缓存后重新落库。宿主机 6379 被占用时以 `-Dopenvpp.it.redis-port=端口` 指定。
若宿主机无法直连 Docker Hub，可经镜像源拉取后重打标准 tag（如 `docker.m.daocloud.io/library/mysql:8.0` → `mysql:8.0`）。

## 网关回环测试（第 06 篇）

```bash
mvn -s settings-openvpp.xml -pl openvpp-gateway -am test
# MqttIngestServiceTest：经公共 broker（broker-cn.emqx.io）回环
# CoapIngestServerTest：本机 127.0.0.1 随机端口回环
```

> 说明：回环测试是**测试专用**通路，与主应用默认本地模拟模式无关；
> 其中 MQTT 用例需可访问公共 broker（断网环境跳过即可），不影响 `java -jar` 零依赖启动。

## 章节 tag 对照（未发布，规划留档）

> **现状（2026-09-19 实测）**：本仓本地与远端（`git ls-remote --tags origin`）**均无任何 tag**，
> 历史仅 2 个提交（squashed `init` + 实战番外 46~48 配套代码），无法忠实重建下表所列快照。
> **当前读者获取入口 = master 分支**（专栏各篇文末与 README 均已按 master 口径表述，本表不再作为承诺）。
> 若作者决定发布冻结快照：在修复验证通过的提交上执行 `git tag -a part1-cognition-r2 -m "..." && git push origin part1-cognition-r2`，
> 并将下表「指向」列与专栏文末口径一并改回 tag——发布前不得在文档中承诺不存在的标签。

| tag | 指向 | 说明 |
|-----|------|------|
| `part1-cognition-r2` | （规划）完整主干里程碑 | 原计划作为读者获取入口：结算四量口径、争议更正版本化留档、回滚内存态补偿、入参前置校验、防重放边界、并发幂等（原子认领 + 纠偏请求留档 + 审计账单 INSERT-only）。**截至 2026-09-19 未创建** |
| `part1-cognition` | （规划）历史快照 | 原计划第一轮冻结快照，未创建 |

> 测试数量**以最新 surefire 报告为准**（`mvn -s settings-openvpp.xml test` 后汇总各模块
> `*/target/surefire-reports/*.txt`；live 用例如 MQTT 公网回环、TSDB benchmark 依赖外部环境，断网时跳过不计入口径）。

## 示例说明

- 示例数据均为虚构，不对应任何真实地区准入或结算规则
- 仓库不内置任何环境地址与凭据


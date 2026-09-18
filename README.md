# openvpp-demo

> 专栏《虚拟电厂系统开发实战：从物联接入到市场化运营》配套示例工程
> 定位：最小可运行实现，不是玩具 Demo，也不是生产代码
> 技术栈：Java 11 · Spring Boot 2.7 · Maven 多模块

## 模块总览

| 模块 | 职责 | 对应专栏篇目 |
|------|------|--------------|
| `openvpp-common` | 统一返回、枚举常量（资源类型/场景），零业务依赖 | 第 01-04 篇 |
| `openvpp-resource` | 资源档案、物模型、设备影子、台账 | 第 04、07、11 篇 |
| `openvpp-assessment` | 能力评估算法（44260 七指标）、评估策略 | 第 02、12、13 篇 |
| `openvpp-aggregator` | VPP 单元、聚合引擎、准入门槛（47241 四指标） | 第 03、14 篇 |
| `openvpp-gateway` | MQTT/CoAP 协议接入 | 第 06 篇 |
| `openvpp-iot` | 设备认证、断网续传 | 第 09、10 篇 |
| `openvpp-dispatch` | 指令链路、策略引擎 | 第 15、16 篇 |
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

```bash
mvn -s settings-openvpp.xml -pl openvpp-app -am -DskipTests package
java -jar openvpp-app/target/openvpp-app-1.0.0.jar

# 四条路径（教学假设数值见 openvpp-app/PARK-DEMO.md 手工核算底稿）
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 正常：600kWh/毛额1200/净实收1200元
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-002&path=DEGRADED"  # 降级：缺口 148.5kW
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"    # 幂等重放：idempotentReplay=true
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-pen&path=NORMAL&declaredKwh=800"  # 非零考核：考核400元、净实收800元

# 争议更正（结算后独立入口；正/负差额与多轮更正均支持，历史版本保留）
curl -X POST "http://localhost:8080/api/v1/demo/dispute?responseId=run-pen&correctedActualKw=380"

# 查询入口（任务/指令/基线/账单，responseId 贯穿关联；账单含 BILL_VERSION 列，V1/V2 并存）
curl "http://localhost:8080/api/v1/tasks"
curl "http://localhost:8080/api/v1/instructions?responseId=run-001"
curl "http://localhost:8080/api/v1/baselines?responseId=run-001"
curl "http://localhost:8080/api/v1/bills?responseId=run-001"

# 重置（清理演示数据后可再跑）
curl -X POST "http://localhost:8080/api/v1/demo/reset"
```

幂等保证：同一 `responseId` 重复触发不重复下发、不重复出账（唯一例外：任务已结算后再以 `path=DISPUTED` 触发 = 争议更正请求，不被幂等拦截）；`responseId` 限 4-50 位（派生指令编号须落在 `VARCHAR(64)` 内，超长前置 400 拒绝）；闭环执行中落库失败会先做内存态补偿（释放容量预占、清除指令镜像）再随事务回滚抛出。重启后任务与账单仍在（H2 文件库 `~/.openvpp/openvpp-db`）。

> 升级说明：`bill_version`（账期版本列，主键含版本）为新增结构。若存在旧版本演示库文件，
> 请先删除 `~/.openvpp/openvpp-db*` 再启动（教学库不做迁移）。

## 网关回环测试（第 06 篇）

```bash
mvn -s settings-openvpp.xml -pl openvpp-gateway -am test
# MqttIngestServiceTest：经公共 broker（broker-cn.emqx.io）回环
# CoapIngestServerTest：本机 127.0.0.1 随机端口回环
```

> 说明：回环测试是**测试专用**通路，与主应用默认本地模拟模式无关；
> 其中 MQTT 用例需可访问公共 broker（断网环境跳过即可），不影响 `java -jar` 零依赖启动。

## 章节 tag 对照

| tag | 指向 | 说明 |
|-----|------|------|
| `part1-cognition-r2` | 完整主干（当前里程碑，**读者获取入口**） | 专栏各篇文末统一引用的工程快照 tag。在 `part1-cognition` 基础上合入第二轮验收修复：结算四量口径（考核扣款从毛额扣除、净实收传导分摊）、结算后争议更正独立入口与账单版本化多轮留档、落库回滚后的内存态补偿与入参前置校验、防重放到期边界统一、遥测重复点去重。测试数量**以最新 surefire 报告为准**（查看：`mvn -s settings-openvpp.xml test` 后汇总各模块 `*/target/surefire-reports/*.txt` 的 Tests run 数；其中 live 用例如 MQTT 公网回环、TSDB benchmark 依赖外部环境，断网时跳过不计入口径）。获取：`git checkout part1-cognition-r2` |
| `part1-cognition` | 历史快照（第一轮，已冻结） | 首个完整主干快照：11 模块 + `openvpp-app` 业务闭环 + 算法番外模块代码。**不含**上述第二轮修复（考核扣款未入实收、争议更正不传导分摊、回滚残留预占、防重放/遥测边界缺陷），仅作历史留档，不再作为获取入口 |

> 说明：专栏文章统一引用 `part1-cognition-r2` 作为工程获取入口（文末获取方式已同步）。
> 后续里程碑将新增 tag 并在此表同步；历史 tag 一经发布不再移动。

## 示例说明

- 示例数据均为虚构，不对应任何真实地区准入或结算规则
- 仓库不内置任何环境地址与凭据


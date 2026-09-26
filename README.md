# openvpp-demo

> 专栏《虚拟电厂系统开发实战：从物联接入到市场化运营》配套示例工程
> 定位：最小可运行的虚拟电厂系统实现，不是玩具 Demo，也不是生产代码

![Java](https://img.shields.io/badge/Java-11-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen)
![Maven](https://img.shields.io/badge/Maven-多模块-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## 📌 项目信息

| 项目 | 说明 |
|------|------|
| 配套专栏 | 《虚拟电厂系统开发实战：从物联接入到市场化运营》 |
| 定位 | 教学/演示工程：各专栏篇目的可运行配套代码，模块与篇目一一对应 |
| 技术栈 | Java 11 · Spring Boot 2.7 · Maven 多模块（11 个业务模块） |
| 持久化 | 单体形态 H2 文件库（零外部依赖）；Docker 形态 MySQL + Redis + EMQX |
| 构建 | `mvn -s settings-openvpp.xml`（公共镜像，不依赖任何私有仓库） |
| 许可证 | [Apache-2.0](LICENSE) |

> **示例说明**：示例数据均为虚构，区域规则口径为教学虚构示例，不对应任何真实地区准入或结算规则。

## 🖼️ 项目概览

一个贯穿案例串起虚拟电厂的完整业务闭环（H2 文件库持久化，读者零外部依赖）：

```
模拟遥测 → 接入校验 → 数据入库 → 能力评估 → 资源聚合 → 响应任务
        → 指令下发 → 执行核验 → 响应量计算 → 结算分摊 → 账单查询
```

- 设备接入：MQTT/CoAP 双协议网关，设备认证、断网续传、多协议接入地图；
- 能力评估：44260 七指标评估、GBDT 预测训推链（Python 副车 + Java 主战）；
- 资源聚合：VPP 单元分组、准入门槛（47241 四指标）、可承诺容量；
- 调度执行：指令链路、策略引擎、MPC 滚动优化、目标分解与评估闭环；
- 结算市场：基线核定、结算四量口径、争议更正版本化、区域规则示例；
- 工程口径：幂等/并发认领/缓存时序/方言自适应等均有单测锚定，教学数值可复算。

## ✅ 模块总览

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

## 📂 项目结构

```
openvpp-demo/
├── README.md                  # 项目主入口（本文件）
├── LICENSE                    # Apache-2.0
├── settings-openvpp.xml       # 工程 Maven settings（公共镜像逃生通道）
├── docker-compose.yml         # 一键交付编排（应用 + MySQL + Redis + EMQX）
├── docs/
│   ├── README.md              # 文档中心总索引
│   ├── snapshots.md           # 章节 tag 对照表
│   ├── tutorials/             # ★ 专栏配套教程（01-07，实操向）
│   └── case-study/            # 贯穿案例手工核算底稿
├── tools/
│   ├── mqtt-burst.sh          # MQTT 上行突发压测脚本
│   └── ai/                    # 零依赖 Python 演示（GBDT/量化/RAG）+ 示例语料
├── openvpp-common|gateway|iot|resource|assessment|aggregator|dispatch|settlement|market|edge
└── openvpp-app/               # 单体启动入口 + 业务闭环编排
```

## 🚀 快速开始

```bash
mvn -s settings-openvpp.xml install -DskipTests
cd openvpp-app && mvn -s ../settings-openvpp.xml spring-boot:run

# 验证
curl http://127.0.0.1:8080/api/v1/system/ping
# {"code":0,"message":"success","data":{"service":"openvpp-demo","status":"UP",...}}
```

零外部依赖：H2 文件库 + 网关本地模拟模式，断网可跑。详细步骤见[教程 01](docs/tutorials/01-quick-start.md)。

## 📚 专栏配套教程

| 教程 | 对应篇目 | 一句话说明 |
|------|----------|-----------|
| [01 快速开始与零依赖启动](docs/tutorials/01-quick-start.md) | 通用 | 构建、启动、健康检查，断网可跑 |
| [02 园区需求响应贯穿案例](docs/tutorials/02-park-demand-response.md) | 第 19 篇 | 11 模块业务闭环：评估 → 聚合 → 指令 → 结算，含幂等/并发/争议更正 |
| [03 Docker Compose 一键交付](docs/tutorials/03-docker-compose.md) | 第 25 篇 | 应用 + MySQL + Redis + EMQX 四容器编排与真实环境回归 |
| [04 网关回环测试](docs/tutorials/04-gateway-loopback.md) | 第 06 篇 | MQTT/CoAP 协议接入链路验证 |
| [05 AI 工具集（GBDT/量化/RAG）](docs/tutorials/05-ai-toolkit.md) | 第 29/30/47 篇 | 三个零依赖 Python 演示与 Java 侧联调 |
| [06 时序库写入基准](docs/tutorials/06-tsdb-benchmark.md) | 时序存储选型 | TDengine vs ClickHouse 同负载对比 |
| [07 MQTT 上行突发压测](docs/tutorials/07-mqtt-burst.md) | 第 18 篇 | 批量上行压测脚本用法与输出解读 |

更多文档：[文档中心](docs/README.md) · [章节 tag 对照](docs/snapshots.md) · [贯穿案例核算底稿](docs/case-study/park-demo.md)

## 🧪 测试口径

- 常规回归：全仓 31 个测试类 / 201 项 / 实际执行 199 项 / 2 项 live 默认跳过
  （TSDB benchmark 与 GBDT 副车联调依赖外部环境，用法见教程 06/05）；
- MQTT 公网回环走公共 broker，**离线不会自动跳过而是断言失败**，断网时请单独排除该用例（教程 04）；
- 真实 MySQL + Redis 集成回归 `MySqlComposeIT`（4 项，需 compose 服务在位，见教程 03）；
- 读者获取入口绑定固定标签：推荐 `part1-cognition-r5` 冻结快照，历史快照差异见
  [章节 tag 对照](docs/snapshots.md)。

## ⚠️ 注意事项

1. **网关模式**：默认 `local` 本地模拟（不连任何外部消息服务）；真实 MQTT/CoAP 接入须显式
   `openvpp.gateway.mode=remote` 并配置 `openvpp.mqtt.broker`，工程不提供任何默认外部地址。
2. **教学库不做迁移**：表结构升级时请删除 `~/.openvpp/openvpp-db*` 再启动。
3. **live/外部依赖用例**：默认跳过或依赖外部服务，不影响常规构建（各教程有专门说明）。
4. **Maven 镜像**：始终带 `-s settings-openvpp.xml`，全局镜像不可达时显式走公共镜像。
5. **内容红线**：本仓库不内置任何环境地址与凭据；自备环境的连接信息
   一律经系统属性/环境变量注入（如教程 06）。

## 📄 许可证

[Apache-2.0](LICENSE)

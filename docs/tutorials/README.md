# 专栏配套教程

> 《虚拟电厂系统开发实战：从物联接入到市场化运营》各篇目的动手实操入口。
> 每篇教程独立可完成，统一结构：**目标 → 前置条件 → 操作步骤 → 验证方法 → 常见问题**。

| 教程 | 对应篇目 | 一句话说明 |
|------|----------|-----------|
| [01 快速开始与零依赖启动](01-quick-start.md) | 通用 | 构建、启动、健康检查，断网可跑 |
| [02 园区需求响应贯穿案例](02-park-demand-response.md) | 第 19 篇 | 11 模块业务闭环：评估 → 聚合 → 指令 → 结算，含幂等/并发/争议更正 |
| [03 Docker Compose 一键交付](03-docker-compose.md) | 第 25 篇 | 应用 + MySQL + Redis + EMQX 四容器编排与真实环境回归 |
| [04 网关回环测试](04-gateway-loopback.md) | 第 06 篇 | MQTT/CoAP 协议接入链路验证 |
| [05 AI 工具集（GBDT/量化/RAG）](05-ai-toolkit.md) | 第 29/30/47 篇 | 三个零依赖 Python 演示与 Java 侧联调 |
| [06 时序库写入基准](06-tsdb-benchmark.md) | 时序存储选型 | TDengine vs ClickHouse 同负载对比（系统属性注入连接） |
| [07 MQTT 上行突发压测](07-mqtt-burst.md) | 第 18 篇 | mosquitto 批量上行压测脚本用法与输出解读 |

配套数值口径：

- 贯穿案例的教学假设数值与逐场景验证记录见 [../case-study/park-demo.md](../case-study/park-demo.md)。
- 各代码快照（tag）的获取方式与差异见 [../snapshots.md](../snapshots.md)。

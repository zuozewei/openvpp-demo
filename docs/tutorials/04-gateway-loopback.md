# 教程 04：网关回环测试（第 06 篇）

> 目标：验证 MQTT/CoAP 两条协议接入链路的回环测试用例，理解测试通路与主应用
> 本地模拟模式的关系。

## 前置条件

- 完成[教程 01](01-quick-start.md)构建。
- MQTT 用例需可访问公共 broker（`broker-cn.emqx.io`）——**断网不会自动跳过而是断言失败**，
  离线环境请单独排除该用例；CoAP 用例走本机回环，无网络要求。

## 操作步骤

```bash
mvn -s settings-openvpp.xml -pl openvpp-gateway -am test
# MqttIngestServiceTest：经公共 broker（broker-cn.emqx.io）回环
# CoapIngestServerTest：本机 127.0.0.1 随机端口回环
```

## 验证方法

两个测试类全绿即协议接入链路（订阅、上行解析、事件总线分发）验证通过。
surefire 报告位于 `openvpp-gateway/target/surefire-reports/`。

## 通路说明

- 回环测试是**测试专用**通路，与主应用默认本地模拟模式（`openvpp.gateway.mode=local`）无关；
- `java -jar` 启动的主应用不连接任何外部消息服务，断网可跑；
- 真实 MQTT/CoAP 协议接入须显式设置 `openvpp.gateway.mode=remote` 并配置
  `openvpp.mqtt.broker`（工程不提供任何默认外部地址）；
- Docker 形态下 EMQX 容器即 remote 模式的接入目标（见[教程 03](03-docker-compose.md)）。

## 常见问题

- **MQTT 用例失败**：先确认宿主机能访问 `broker-cn.emqx.io`（公共 broker，教学专用）；
  长期离线环境可用 `-Dtest=CoapIngestServerTest` 只跑本机回环。
- **想压测上行吞吐**：见[教程 07：MQTT 上行突发压测](07-mqtt-burst.md)。

# 教程 07：MQTT 上行突发压测（第 18 篇）

> 目标：用 `tools/mqtt-burst.sh` 向 broker 批量注入上行遥测，观察网关接入链路的吞吐表现。

## 前置条件

- 安装 `mosquitto_pub`（[mosquitto.org/download](https://mosquitto.org/download/)）；
- 一个可用的 MQTT broker。两种选择：
  - **教学公共 broker**：默认 `broker-cn.emqx.io`（无需自建，勿用于正式压测）；
  - **自备 broker**：如[教程 03](03-docker-compose.md)拉起的 EMQX（`localhost:1883`）。

## 操作步骤

```bash
chmod +x tools/mqtt-burst.sh

# 用法：./mqtt-burst.sh [broker_host] [消息数] [设备数]
./tools/mqtt-burst.sh                                   # 默认：公共 broker / 1000 条 / 100 台设备
./tools/mqtt-burst.sh localhost 5000 100               # 打教程 03 拉起的 EMQX
```

脚本行为：每条消息一个 `mosquitto_pub` 后台进程（QoS 0），主题 `openvpp/bench-dev-N/telemetry`，
payload 携带毫秒时间戳、序号与模拟功率；每 100 条收敛一次并发，防止本地进程爆炸。

## 验证方法

脚本结束输出总耗时与吞吐：

```
[BURST] broker=localhost messages=5000 devices=100
[BURST] sent 5000 messages in 8123ms (615 msg/s)
```

- 吞吐数字受本机进程创建成本主导（每条消息一个进程），它衡量的是**注入能力**，
  不是 broker 或网关的上限；要测网关上限应改用持久连接的压测客户端。
- 配合[教程 03](03-docker-compose.md)的 EMQX，可在 EMQX 控制台（`http://localhost:18083`）
  观察消息速率曲线，或在应用日志中看到 `[INGRESS-MOCK]` 教学消费者逐条落日志。

## 常见问题

- **mosquitto_pub: command not found**：安装 mosquitto 客户端（macOS: `brew install mosquitto`；
  Debian/Ubuntu: `apt install mosquitto-clients`）。
- **公共 broker 连接失败**：公共 broker 不保证可用性，改打本地 EMQX。
- **想改主题前缀对接自己的网关订阅**：修改脚本内 `-t "openvpp/bench-dev-$dev/telemetry"`，
  与网关订阅通配 `openvpp/+/telemetry|event|ack` 保持同构。

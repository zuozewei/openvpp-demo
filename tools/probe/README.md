# probe：第 06 篇异常样本与耗时观测探针

配套专栏《虚拟电厂系统开发实战》第 06 篇「异常报文样本」「MQTT 回环耗时」两节的可复跑记录。

## 文件

- `IngestAnomalyProbe.java`：探针源码。起真实 `MqttIngestService`（被测网关）经公共 broker，发布六类异常样本 + 对照样本 + 约 1MB 大报文，逐条记录计时点（纪元毫秒）、报文字节数、接收时刻与异常码。
- `run-2026-09-27.log`：2026-09-27 运行的原始日志（stdout 全量，含网关侧 error 日志与探针汇总表 REC 行）。

## 运行环境（该次记录）

- macOS（darwin 24.6.0 x64）、JDK 11.0.21、Maven 3.9.6
- 工程 HEAD：`77b9156`（openvpp-gateway 模块）
- broker：`tcp://broker-cn.emqx.io:1883`（公共测试 broker，未认证/未配置 ACL）
- 编译运行（仓库根目录；全新检出须先编译 gateway 模块生成 target/classes）：

```bash
mvn -s settings-openvpp.xml -pl openvpp-gateway -am compile
mvn -s settings-openvpp.xml -pl openvpp-gateway -am dependency:build-classpath -Dmdep.outputFile=/tmp/ovpp-cp.txt -q
CP="$(cat /tmp/ovpp-cp.txt):openvpp-gateway/target/classes:$HOME/.m2/repository/org/slf4j/slf4j-simple/2.0.0/slf4j-simple-2.0.0.jar"
javac -encoding UTF-8 -cp "$CP" -d /tmp/probe tools/probe/IngestAnomalyProbe.java
java -cp "/tmp/probe:$CP" IngestAnomalyProbe
```

如需指定 broker 地址（默认 `tcp://broker-cn.emqx.io:1883`）：

```bash
java -cp "/tmp/probe:$CP" IngestAnomalyProbe tcp://broker-cn.emqx.io:1883
```

## 该次观测结论（详见日志）

| 样本 | 字节 | 结果 |
|---|---:|---|
| S1 非法 JSON | 10 | 网关 error 日志（Unrecognized token），不投递，循环存活 |
| S2 缺 ts/seq | 23 | 照常投递，seq=null ts=null |
| S3 两段主题 | 23 | 未命中订阅 `openvpp/+/+`，网关无日志、无投递 |
| S4 未知类型段 | 23 | `parseType` 异常进 error 日志，不投递 |
| S6 重复/旧 seq | 50×3 | 首发/同 seq/旧 seq 均照收并打日志，循环存活 |
| CTRL1/CTRL2 | 50/51 | 均正常接收（CTRL2 证明 1MB 断连后订阅端存活） |
| S5 约 1MB | 1,048,625 | 发布时客户端报告连接意外丢失（Paho 32109，cause=EOFException），网关未观察到报文；断开方与原因需 broker／网络侧日志核对，客户端侧证据无法归因 |

耗时计时点：订阅端连接并完成订阅 1439ms（计时含 `service.connect()` 中的订阅步骤）、发布端建连 823ms；全程 wall 23305ms（含样本间隔与观察窗口）。

## 已知测量限制

- 探针用"就近匹配"把接收回调关联到最近一条未确认样本：发布有序且间隔 300ms 时可区分样本归属，但 `latencyMs` 存在竞态（个别样本 RECV 早于 publish 返回，如日志中 S6c 为 -7ms）——**单条 latency 数值仅作量级参考，不作精确时延**；干净的时延观测只有 S2（133ms，sentAt 在 publish 返回后记录）。
- 公共 broker 无认证与 ACL，S3/S4 的"无投递"结论区分了"未命中订阅"（S3）与"解析异常"（S4，error 日志可查）两种形态。
- broker 侧日志不可得：S5 断连由哪一方发起、为何发生，无法从客户端证据判定，如需归因请自建 broker 复跑并核对服务端与网络侧日志。

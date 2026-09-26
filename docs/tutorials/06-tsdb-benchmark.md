# 教程 06：时序库写入基准（TSDB Benchmark）

> 目标：在你自备的 TDengine 与 ClickHouse 环境上，跑同一套写入/查询负载做选型对比。
> **连接地址与凭据全部经系统属性注入，仓库不内置任何环境信息。**

## 前置条件

- 可访问的 TDengine 与 ClickHouse 实例（版本以 `openvpp-iot` 对应驱动适配为准）；
- 目标库 `openvpp_demo` 可由所给账号创建表。

## 基准场景

100 台设备 × 5 属性 × 1 秒/点 × 60 秒 = **30000 点批量写入**（分 10 批，模拟秒级批量入库节奏）；
再按"单设备单属性时间窗"查询 600 点。两个实现跑同一套负载，输出各自写入吞吐与查询耗时。

## 操作步骤

```bash
mvn -s settings-openvpp.xml -pl openvpp-iot test -Dtest=TsdbBenchmarkLiveTest -Dtsdb.live=true \
  -Dtsdb.tdengine.host=<host> -Dtsdb.tdengine.port=<port> \
  -Dtsdb.tdengine.user=<user> -Dtsdb.tdengine.password=<password> \
  -Dtsdb.clickhouse.host=<host> -Dtsdb.clickhouse.port=<port> \
  -Dtsdb.clickhouse.user=<user> -Dtsdb.clickhouse.password=<password>
```

## 系统属性一览

| 属性 | 说明 |
|------|------|
| `tsdb.live` | 必须 `true` 才执行（默认跳过，不依赖外部环境的常规构建不受影响） |
| `tsdb.tdengine.host` / `.port` / `.user` / `.password` | TDengine 连接四要素，全部必填 |
| `tsdb.clickhouse.host` / `.port` / `.user` / `.password` | ClickHouse 连接四要素，全部必填 |

任一属性缺失时用例直接失败并提示缺失项（不设默认值，防止误连）。

## 验证方法

日志中的 `[BENCH]` 行给出每个实现的写入点数/耗时（点/秒）与查询点数/耗时：

```
[BENCH] TDengine 写入 30000 点 / X ms（Y 点/秒）| 查询 600 点 / Z ms
[BENCH] ClickHouse 写入 30000 点 / X ms（Y 点/秒）| 查询 600 点 / Z ms
```

用例断言查询点数与时间窗一致（600 点），两个库均通过即基准有效。

## 常见问题

- **连接失败**：确认 host/port 可达、账号密码正确；端口注意区分原生端口与 REST 端口
  （ClickHouse 走 HTTP 的实例给 HTTP 端口）。
- **想只跑其中一个库**：暂不支持单库开关，基准设计是同负载对照，两个都跑才可比。
- **结论参考**：选型结论与专栏时序存储篇一致——写入吞吐与窗口查询各有胜负，
  以你自己环境的数据为准，不要引用他人数字。

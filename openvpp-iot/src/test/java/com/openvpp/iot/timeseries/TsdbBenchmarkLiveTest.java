package com.openvpp.iot.timeseries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 时序基准对比 —— 默认跳过，需自备 TDengine/ClickHouse 环境。
 * 连接地址与凭据全部经系统属性注入，仓库不内置任何环境信息，缺属性时直接失败并提示缺失项：
 * mvn -s settings-openvpp.xml -pl openvpp-iot test -Dtest=TsdbBenchmarkLiveTest -Dtsdb.live=true \
 *   -Dtsdb.tdengine.host=&lt;host&gt; -Dtsdb.tdengine.port=&lt;port&gt; \
 *   -Dtsdb.tdengine.user=&lt;user&gt; -Dtsdb.tdengine.password=&lt;password&gt; \
 *   -Dtsdb.clickhouse.host=&lt;host&gt; -Dtsdb.clickhouse.port=&lt;port&gt; \
 *   -Dtsdb.clickhouse.user=&lt;user&gt; -Dtsdb.clickhouse.password=&lt;password&gt;
 *
 * 基准场景：100 台设备 × 5 属性 × 1 秒/点 × 60 秒 = 30000 点批量写入；
 * 再按"单设备单属性时间窗"查询 600 点。两个实现跑同一套负载。
 */
class TsdbBenchmarkLiveTest {

    private static final Logger log = LoggerFactory.getLogger(TsdbBenchmarkLiveTest.class);

    private static final int DEVICES = 100;
    private static final String[] PROPS = {"power", "soc", "temp", "voltage", "current"};
    private static final int SECONDS = 60;
    private static final long BASE_TS = 1_758_000_000_000L;

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "tsdb.live", matches = "true")
    void benchmark() {
        run(new TdengineTsdbWriter(required("tsdb.tdengine.host"), intProperty("tsdb.tdengine.port"), "openvpp_demo",
                required("tsdb.tdengine.user"), required("tsdb.tdengine.password")));
        run(new ClickHouseTsdbWriter(required("tsdb.clickhouse.host"), intProperty("tsdb.clickhouse.port"), "openvpp_demo",
                required("tsdb.clickhouse.user"), required("tsdb.clickhouse.password")));
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("live 基准缺少系统属性 -D" + key + "=<值>（仓库不内置环境地址与凭据）");
        }
        return value;
    }

    private static int intProperty(String key) {
        return Integer.parseInt(required(key));
    }

    private void run(TsdbWriter writer) {
        writer.initSchema();

        List<TelemetryPoint> batch = new java.util.ArrayList<>();
        for (int d = 0; d < DEVICES; d++) {
            for (String prop : PROPS) {
                for (int s = 0; s < SECONDS; s++) {
                    batch.add(new TelemetryPoint("bench-dev-" + d, prop,
                            BASE_TS + s * 1000L, 100.0 + Math.random() * 50));
                }
            }
        }

        long writeStart = System.currentTimeMillis();
        // 分 10 批写入，模拟秒级批量入库节奏
        int batchSize = batch.size() / 10;
        for (int i = 0; i < batch.size(); i += batchSize) {
            writer.writeBatch(batch.subList(i, Math.min(i + batchSize, batch.size())));
        }
        long writeMs = System.currentTimeMillis() - writeStart;

        long queryStart = System.currentTimeMillis();
        List<TelemetryPoint> result = writer.queryRange("bench-dev-0", "power",
                BASE_TS, BASE_TS + SECONDS * 1000L);
        long queryMs = System.currentTimeMillis() - queryStart;

        log.info("[BENCH] {} | 写入 {} 点 / {} ms（{} 点/秒）| 查询 {} 点 / {} ms",
                writer.name(), batch.size(), writeMs,
                batch.size() * 1000L / Math.max(writeMs, 1),
                result.size(), queryMs);

        org.junit.jupiter.api.Assertions.assertEquals(SECONDS, result.size(),
                writer.name() + " 查询点数与时间窗不符");
    }
}

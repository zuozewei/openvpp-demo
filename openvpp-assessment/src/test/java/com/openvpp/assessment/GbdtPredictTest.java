package com.openvpp.assessment;

import com.openvpp.assessment.predict.GbdtClient;
import com.openvpp.assessment.predict.GbdtTransport;
import com.openvpp.assessment.predict.PredictAlgoEngine;
import com.openvpp.assessment.predict.PredictCalendar;
import com.openvpp.assessment.predict.PvPhysics;
import com.openvpp.assessment.predict.WeatherFactor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 47 篇 GBDT 训推链单测：ready 探测 / 失败返 null / 点数校验 /
 * 不降级抛不可达 / 气象物理正式算法 / RULE 默认禁用 / 夜间清零与装机截断 /
 * 跨语言公式契约（与 tools/ai/gbdt_demo.py 对齐值）。
 */
class GbdtPredictTest {

    /** 桩传输：可编程的 /health 与 /predict 应答 */
    static final class StubTransport implements GbdtTransport {
        String healthBody = "{\"code\":200,\"ready\":true}";
        String predictBody = "{\"code\":200,\"algorithm\":\"GBDT_PV_V1\",\"predictKw\":[1.5,2.5,3.5]}";
        boolean failPredict = false;

        @Override
        public String get(String path) throws IOException {
            if (healthBody == null) {
                throw new IOException("connection refused");
            }
            return healthBody;
        }

        @Override
        public String post(String path, String jsonBody) throws IOException {
            if (failPredict || predictBody == null) {
                throw new IOException("connection refused");
            }
            return predictBody;
        }
    }

    @Test
    void ready探测失败只返回false不上抛() {
        StubTransport stub = new StubTransport();
        stub.healthBody = null;   // 连接拒绝
        GbdtClient client = new GbdtClient(stub, true);
        assertFalse(client.ready(), "副车未启动时 ready=false（debug 级，不上抛）");
        assertFalse(new GbdtClient(stub, false).ready(), "开关关闭直接 false");
    }

    @Test
    void 推理失败返回null而不抛异常() {
        StubTransport stub = new StubTransport();
        stub.failPredict = true;
        GbdtClient client = new GbdtClient(stub, true);
        assertNull(client.predictPv(PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8)),
                "一切异常转 null，降级决策收敛在策略层");
    }

    @Test
    void 返回点数与入参不匹配返回null() {
        StubTransport stub = new StubTransport();
        // 应答 3 点，入参 4 点 → 契约破坏症状显性化
        GbdtClient client = new GbdtClient(stub, true);
        List<Map<String, Object>> points =
                PredictAlgoEngine.pvPoints(4, 240, 1.0, 28, 2, 1, 180, 8);
        assertNull(client.predictPv(points), "点数不匹配 → null");
    }

    @Test
    void 正常推理解析predictKw数组() {
        GbdtClient client = new GbdtClient(new StubTransport(), true);
        double[] kw = client.predictPv(
                PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8));
        assertNotNull(kw);
        assertArrayEquals(new double[]{1.5, 2.5, 3.5}, kw, 0.001);
    }

    @Test
    void 正式算法失败默认不降级() {
        StubTransport stub = new StubTransport();
        stub.healthBody = null;   // 副车没起
        PredictAlgoEngine engine = new PredictAlgoEngine(
                new GbdtClient(stub, true),
                slots -> new double[]{1, 1, 1}, slots -> null, false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.predictPv(
                        PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8), 240, 3),
                "宁可报不可达，不输出无意义的假数");
        assertTrue(ex.getMessage().contains("预测效果不可达"));
        assertTrue(ex.getMessage().contains("请先启动推理副车"), "报错要能定位到起服务");
    }

    @Test
    void GBDT关闭时气象物理是正式算法而非降级() {
        PredictAlgoEngine engine = new PredictAlgoEngine(
                new GbdtClient(new StubTransport(), false),
                slots -> new double[]{100, 110, 120}, slots -> null, false);
        PredictAlgoEngine.PredictResult r = engine.predictPv(
                PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8), 240, 3);
        assertEquals(PredictAlgoEngine.WEATHER_PV_V1, r.algorithm());
        assertArrayEquals(new double[]{100, 110, 120}, r.kw(), 0.001);
    }

    @Test
    void 应急假曲线默认禁用() {
        // GBDT 关闭 + 气象物理也失败 + RULE 未开 → 不可达
        PredictAlgoEngine engine = new PredictAlgoEngine(
                new GbdtClient(new StubTransport(), false),
                slots -> null, slots -> null, false);
        assertThrows(IllegalStateException.class,
                () -> engine.predictPv(
                        PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8), 240, 3));
        // 显式开启才兜底
        PredictAlgoEngine withRule = new PredictAlgoEngine(
                new GbdtClient(new StubTransport(), false),
                slots -> null, slots -> null, true);
        PredictAlgoEngine.PredictResult r = withRule.predictPv(
                PredictAlgoEngine.pvPoints(3, 240, 1.0, 28, 2, 1, 180, 8), 240, 3);
        assertEquals(PredictAlgoEngine.RULE_V1, r.algorithm(), "联调应急，非正式口径");
    }

    @Test
    void 夜间清零与装机截断() {
        // 后处理：solar_shape=0 的点清零；残差再大也不越装机
        List<Map<String, Object>> points = new ArrayList<>();
        Map<String, Object> night = new LinkedHashMap<>();
        night.put("solar_shape", 0.0);
        night.put("rated_kw", 240);
        night.put("irradiance_wm2", 0);
        night.put("module_temp", 20);
        points.add(night);
        Map<String, Object> noon = new LinkedHashMap<>();
        noon.put("solar_shape", 0.9);
        noon.put("rated_kw", 240);
        noon.put("irradiance_wm2", 900);
        noon.put("module_temp", 40);
        points.add(noon);
        double[] out = PredictAlgoEngine.PvPostProcess.apply(new double[]{999, 999}, points, 240);
        assertEquals(0.0, out[0], 0.001, "夜间硬清零");
        assertEquals(240.0, out[1], 0.001, "物理基线+残差被装机截断");
    }

    @Test
    void 跨语言公式契约对齐值() {
        // 与 tools/ai/gbdt_demo.py selfcheck 输出对齐（seed=0）：正午 shape≈0.898、夜间 0
        double noon = PvPhysics.solarShape(180, 12.2, PvPhysics.DEMO_LAT, PvPhysics.DEMO_LON, 0);
        assertEquals(0.898, noon, 0.01, "夏至正午太阳形状（与 Python 侧一致）");
        assertEquals(0.0, PvPhysics.solarShape(180, 23.0, PvPhysics.DEMO_LAT, PvPhysics.DEMO_LON, 0),
                0.0, "夜间硬性 0");
        // 组件温度公式对齐：环温 28、GHI 900、风速 3 → 28+8+10.8−0.4 = 46.4
        assertEquals(46.4, PvPhysics.moduleTemp(28, 900, 3), 0.001);
        // 天气折减系数表对齐
        assertEquals(1.00, WeatherFactor.of("晴"), 0.001);
        assertEquals(0.78, WeatherFactor.of("多云"), 0.001);
        assertEquals(0.58, WeatherFactor.of("阴"), 0.001);
        assertEquals(0.68, WeatherFactor.of("小雨"), 0.001);
        // 三态日历
        PredictCalendar cal = new PredictCalendar(java.util.Collections.singleton(
                LocalDate.of(2026, 10, 1)));
        assertEquals(2, cal.dateType(LocalDate.of(2026, 10, 1)), "法定节假日");
        assertEquals(1, cal.dateType(LocalDate.of(2026, 10, 3)), "自然周末");
        assertEquals(0, cal.dateType(LocalDate.of(2026, 10, 5)), "工作日");
    }

    /** live 联调用例：需先启动 python tools/ai/gbdt_demo.py 并设置 GBDT_LIVE=1，默认跳过。 */
    @Test
    void live副车联调() {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("GBDT_LIVE")),
                "live 演示默认跳过：先启动 tools/ai/gbdt_demo.py 并设置 GBDT_LIVE=1");
        GbdtClient client = new GbdtClient(
                new com.openvpp.assessment.predict.HttpGbdtTransport("http://127.0.0.1:43317",
                        8000), true);
        org.junit.jupiter.api.Assumptions.assumeTrue(client.ready(), "副车未启动");
        double[] kw = client.predictPv(
                PredictAlgoEngine.pvPoints(8, 240, 1.0, 28, 2, 1, 180, 8));
        assertNotNull(kw);
        assertEquals(8, kw.length);
    }
}

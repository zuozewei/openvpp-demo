package com.openvpp.assessment.predict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预测算法引擎 —— 专栏第 47 篇混合策略路由，灵魂是「不降级的勇气」：
 * 正式算法失败默认不降级，宁可报「预测效果不可达」，不输出无意义的假数。
 *
 * RULE_V1（应急假曲线）仅显式开启才兜底——它与第 29 篇的降级兜底不矛盾：
 * 第 29 篇降的是性能形态（输出仍是真预测），这里拒的是能力冒充。
 */
public class PredictAlgoEngine {

    /** 算法码（与 Python 副车 meta.json 的 algorithm 字段一致） */
    public static final String GBDT_PV_V1 = "GBDT_PV_V1";
    public static final String WEATHER_PV_V1 = "WEATHER_PV_V1";
    public static final String GBDT_LOAD_V1 = "GBDT_LOAD_V1";
    public static final String SIMILAR_DAY_V1 = "SIMILAR_DAY_V1";
    public static final String RULE_V1 = "RULE_V1";

    private final GbdtClient gbdt;
    /** GBDT 关闭时的正式算法：气象物理 + 相似日混合（光伏）/相似日均值（负荷） */
    private final Fallback weatherPv;
    private final Fallback similarDayLoad;
    private final boolean ruleFallbackEnabled;

    /** 降级算子：入参逐时隙，出预测功率（教学版由调用方注入简单实现）。 */
    public interface Fallback {
        double[] predict(int slotCount);
    }

    public PredictAlgoEngine(GbdtClient gbdt, Fallback weatherPv, Fallback similarDayLoad,
                             boolean ruleFallbackEnabled) {
        this.gbdt = gbdt;
        this.weatherPv = weatherPv;
        this.similarDayLoad = similarDayLoad;
        this.ruleFallbackEnabled = ruleFallbackEnabled;
    }

    /**
     * 光伏预测路由。
     *
     * @param points 13 特征点
     * @param ratedKw 装机（截断与 RULE 兜底用）
     * @return 预测结果（算法码 + 功率列）
     */
    public PredictResult predictPv(List<Map<String, Object>> points, double ratedKw,
                                   int slotCount) {
        if (gbdt.enabled()) {
            if (!gbdt.ready()) {
                throw new IllegalStateException(
                        "预测效果不可达：发电正式算法未能产出有效结果（请先启动推理副车）");
            }
            double[] raw = gbdt.predictPv(points);
            if (raw == null) {
                throw new IllegalStateException(
                        "预测效果不可达：发电正式算法未能产出有效结果（推理失败或点数不匹配）");
            }
            double[] post = PvPostProcess.apply(raw, points, ratedKw);
            return new PredictResult(GBDT_PV_V1, post);
        }
        // GBDT 关闭：气象物理 + 相似日混合是正式算法（不是降级）
        double[] weather = weatherPv.predict(slotCount);
        if (weather == null) {
            return ruleOrThrow(ratedKw, slotCount);
        }
        return new PredictResult(WEATHER_PV_V1, weather);
    }

    /** 负荷预测路由（储能复用负荷模型，仅算法码区分）。 */
    public PredictResult predictLoad(List<Map<String, Object>> points, int slotCount,
                                     boolean storage) {
        String algoCode = storage ? GBDT_LOAD_V1 : GBDT_LOAD_V1;
        if (gbdt.enabled()) {
            double[] raw = storage ? gbdt.predictLoad(points) : gbdt.predictLoad(points);
            if (raw == null) {
                throw new IllegalStateException(
                        "预测效果不可达：负荷正式算法未能产出有效结果");
            }
            return new PredictResult(algoCode, raw);
        }
        double[] similar = similarDayLoad.predict(slotCount);
        if (similar == null) {
            return ruleOrThrow(0, slotCount);
        }
        return new PredictResult(SIMILAR_DAY_V1, similar);
    }

    private PredictResult ruleOrThrow(double ratedKw, int slotCount) {
        if (!ruleFallbackEnabled) {
            throw new IllegalStateException(
                    "预测效果不可达：无可用正式算法且应急假曲线未启用");
        }
        // RULE_V1 = 装机 × 形状正弦（联调应急，非正式口径）
        double[] shape = new double[slotCount];
        for (int t = 0; t < slotCount; t++) {
            double hour = t * 0.25;
            shape[t] = ratedKw * PvPhysics.solarShape(180, hour, PvPhysics.DEMO_LAT,
                    PvPhysics.DEMO_LON, 1);
        }
        return new PredictResult(RULE_V1, shape);
    }

    /** 预测结果。 */
    public static final class PredictResult {
        private final String algorithm;
        private final double[] kw;

        PredictResult(String algorithm, double[] kw) {
            this.algorithm = algorithm;
            this.kw = kw;
        }

        public String algorithm() {
            return algorithm;
        }

        public double[] kw() {
            return kw.clone();
        }
    }

    /** Java 侧推理后处理：夜间清零、物理基线护栏、装机截断（供单测直验）。 */
    public static final class PvPostProcess {
        private PvPostProcess() {
        }

        public static double[] apply(double[] raw, List<Map<String, Object>> points, double ratedKw) {
            double[] out = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                Map<String, Object> p = points.get(i);
                double shape = ((Number) p.get("solar_shape")).doubleValue();
                double rated = ((Number) p.get("rated_kw")).doubleValue();
                double ghi = ((Number) p.get("irradiance_wm2")).doubleValue();
                double modTemp = ((Number) p.get("module_temp")).doubleValue();
                if (shape <= 0) {
                    out[i] = 0;   // 夜间硬清零
                    continue;
                }
                // physics_residual：基线 + 残差，再夹 [0, rated]
                double baseline = PvPhysics.baselineKw(rated, ghi, modTemp);
                out[i] = Math.max(0, Math.min(ratedKw, baseline + raw[i]));
            }
            return out;
        }
    }

    /** 13 特征点构造器（键序即契约：与 Python FEATURE_NAMES 一致）。 */
    public static List<Map<String, Object>> pvPoints(int slotCount, double ratedKw,
                                                     double irrFactor, double tempC,
                                                     double windMs, long seed, int dayOfYear,
                                                     double startHour) {
        List<Map<String, Object>> points = new ArrayList<>(slotCount);
        for (int t = 0; t < slotCount; t++) {
            double hour = startHour + t * 0.25;
            double shape = PvPhysics.solarShape(dayOfYear, hour, PvPhysics.DEMO_LAT,
                    PvPhysics.DEMO_LON, seed);
            double ghi = PvPhysics.irradianceFallback(shape, irrFactor);
            double modTemp = PvPhysics.moduleTemp(tempC, ghi, windMs);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("hour", (int) Math.floor(hour));
            p.put("minute", (t % 4) * 15);
            p.put("weekday", 1);
            p.put("is_weekend", 0);
            p.put("solar_shape", Math.round(shape * 10000.0) / 10000.0);
            p.put("rated_kw", ratedKw);
            p.put("irr_factor", irrFactor);
            p.put("temp", tempC);
            p.put("lag_same_hm", 0);
            p.put("lag_recent", 0);
            p.put("irradiance_wm2", Math.round(ghi * 100.0) / 100.0);
            p.put("module_temp", Math.round(modTemp * 100.0) / 100.0);
            p.put("wind_speed", windMs);
            points.add(p);
        }
        return points;
    }
}

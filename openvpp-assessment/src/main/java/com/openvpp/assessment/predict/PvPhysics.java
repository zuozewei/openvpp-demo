package com.openvpp.assessment.predict;

/**
 * 光伏物理基线 —— 专栏第 47 篇「物理基线 + GBDT 学残差」的物理侧。
 *
 * 太阳几何/组件温度/温度降额是 Java 与 Python 两侧手工对齐的「公式契约」——
 * 公式错了一般错得显眼（昼夜颠倒、量级离谱），比 schema 错位更容易被发现。
 * 与 tools/ai/gbdt_demo.py 的 solar_shape()/module_temp() 逐系数一致。
 */
public final class PvPhysics {

    /** 教学默认纬度（北方城市，真实工程按站点配置） */
    public static final double DEMO_LAT = 39.12;
    public static final double DEMO_LON = 117.20;

    private PvPhysics() {
    }

    /**
     * 太阳高度角形状（0~1）。
     *
     * 赤纬 δ = 23.45°·sin(360°/365·(n−81))；时角由真太阳时正午
     * noon = 12 + (120−经度)/15 修正；夜间（高度角 ≤ 0）硬性为 0。
     * 站点微扰 wobble 使每个站点的曲线可复现地略有差异。
     *
     * @param dayOfYear 年序日（1~365）
     * @param hour      小时（0~24，含小数）
     * @param latDeg    纬度
     * @param lonDeg    经度（东经）
     * @param seed      站点种子（资源 ID）
     */
    public static double solarShape(int dayOfYear, double hour, double latDeg, double lonDeg,
                                    long seed) {
        double lat = Math.toRadians(latDeg);
        double decl = Math.toRadians(23.45 * Math.sin(Math.toRadians(360.0 / 365 * (dayOfYear - 81))));
        double noon = 12 + (120 - lonDeg) / 15;
        double ha = Math.toRadians(15 * (hour - noon));
        double sinEl = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(ha);
        if (sinEl <= 0) {
            return 0;   // 日出前/日落后硬性 0
        }
        double wobble = 0.025 * Math.sin((hour * 3.1 + seed % 97) * 0.6);
        return Math.max(0, Math.min(1, sinEl * 0.95 + wobble));
    }

    /** 组件温度 = 环温 + 8.0 + 辐照比×12.0 − 0.4×max(0, 风速−2)。 */
    public static double moduleTemp(double ambientC, double ghiWm2, double windMs) {
        return ambientC + 8.0 + (ghiWm2 / 1000.0) * 12.0
                - 0.4 * Math.max(0, windMs - 2);
    }

    /** 组件温度降额：≤25℃ 为 1.0，每升 1℃ 降 0.4%，下限 0.85。 */
    public static double tempDerate(double moduleTempC) {
        if (moduleTempC <= 25) {
            return 1.0;
        }
        return Math.max(0.85, 1 - (moduleTempC - 25) * 0.004);
    }

    /** 光伏物理基线 = 装机 × (GHI/1000) × 温度降额。 */
    public static double baselineKw(double ratedKw, double ghiWm2, double moduleTempC) {
        return ratedKw * (ghiWm2 / 1000.0) * tempDerate(moduleTempC);
    }

    /** GHI 缺测时的兜底估算：1000 × 形状 × 天气折减系数。 */
    public static double irradianceFallback(double shape, double irrFactor) {
        return 1000 * shape * irrFactor;
    }
}

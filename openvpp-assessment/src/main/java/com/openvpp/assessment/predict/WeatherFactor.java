package com.openvpp.assessment.predict;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气中间表示 —— 专栏第 47 篇跨语言契约的「共享词汇」。
 *
 * 生产链路：气象 API 的 WMO 码 → 中文天气 → 辐照折减系数。
 * 中文天气文字是 Python/Java 两侧共享的中间表示，折减表两侧逐字一致。
 */
public final class WeatherFactor {

    private static final Map<String, Double> FACTORS = buildFactors();

    private WeatherFactor() {
    }

    private static Map<String, Double> buildFactors() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("晴", 1.00);
        m.put("多云", 0.78);
        m.put("阴", 0.58);
        m.put("小雨", 0.68);
        m.put("小雪", 0.68);
        m.put("中雨", 0.45);
        m.put("中雪", 0.45);
        m.put("大雨", 0.35);
        m.put("雨雪霾", 0.35);
        return m;
    }

    /** 中文天气 → 辐照折减系数，未知/缺省 0.7。 */
    public static double of(String weatherText) {
        if (weatherText == null || weatherText.isEmpty()) {
            return 0.7;
        }
        for (Map.Entry<String, Double> e : FACTORS.entrySet()) {
            if (weatherText.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return 0.7;
    }

    /** WMO 天气码 → 中文（与 Python 侧 wmo_to_text 对齐；未覆盖码兜底「多云」）。 */
    public static String wmoToText(int code) {
        if (code == 0) {
            return "晴";
        }
        if (code == 1 || code == 2) {
            return "多云";
        }
        if (code == 3) {
            return "阴";
        }
        if (code == 45 || code == 48) {
            return "霾";
        }
        if ((code >= 51 && code <= 61) || code == 80 || code == 81) {
            return "小雨";
        }
        if (code >= 63 && code <= 65 || code == 82) {
            return "中雨";
        }
        if (code >= 95) {
            return "大雨";
        }
        return "多云";
    }

    /** 天气分档置信带（专栏第 12 篇口径：未校准前只是分档，不承诺覆盖率）。 */
    public static double uncertaintyBand(String weatherText) {
        double f = of(weatherText);
        if (f >= 1.0) {
            return 0.08;   // 晴 ±8%
        }
        if (f >= 0.78) {
            return 0.12;   // 多云 ±12%
        }
        if (f >= 0.58) {
            return 0.18;   // 阴雾 ±18%
        }
        return 0.22;       // 雨雪雷 ±22%
    }
}

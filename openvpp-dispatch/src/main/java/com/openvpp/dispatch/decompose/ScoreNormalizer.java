package com.openvpp.dispatch.decompose;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 得分归一化 —— 专栏第 46 篇「宁可平均也不抛异常」纪律。
 *
 * 负值清零；总和 ≤ 0 时均分 1/n；否则按 v/sum 归一（保持传入顺序）。
 * 分解入口是运营点按钮就跑的功能，任何输入都不允许除零白屏。
 */
public final class ScoreNormalizer {

    private ScoreNormalizer() {
    }

    /** 原始分 → 归一化份额（顺序与入参一致，和为 1；全零时均分）。 */
    public static Map<String, Double> normalize(Map<String, Double> rawScores) {
        Map<String, Double> result = new LinkedHashMap<>();
        double sum = 0;
        for (Map.Entry<String, Double> e : rawScores.entrySet()) {
            double v = Math.max(0, e.getValue());
            sum += v;
        }
        if (sum <= 0) {
            double even = 1.0 / rawScores.size();
            for (String id : rawScores.keySet()) {
                result.put(id, even);
            }
            return result;
        }
        for (Map.Entry<String, Double> e : rawScores.entrySet()) {
            result.put(e.getKey(), Math.max(0, e.getValue()) / sum);
        }
        return result;
    }
}

package com.openvpp.assessment.predict;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GBDT 推理客户端 —— 专栏第 47 篇 Java 调用侧。
 *
 * 失败语义：一切异常（连接拒绝/非 200/JSON 坏/返回点数与入参不匹配）都返回
 * null 而不抛异常——降级决策收敛在策略层（PredictAlgoEngine），客户端只报事实。
 * 每次正式推理前先打一次 /health：双请求换精确诊断（「请先启动推理服务」）。
 */
public class GbdtClient {

    private final GbdtTransport transport;
    private final boolean enabled;

    public GbdtClient(GbdtTransport transport, boolean enabled) {
        this.transport = transport;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** /health 的 ready 字段；任何失败返回 false（只 debug 不上抛）。 */
    public boolean ready() {
        if (!enabled) {
            return false;
        }
        try {
            String body = transport.get("/health");
            return body.contains("\"ready\"") && body.contains("\"ready\":true");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 13 特征点批量预测光伏功率。
     *
     * @param points 每个 point 为 13 键特征（键名与 Python FEATURE_NAMES 对齐）
     * @return 预测 kW 数组；失败或点数不匹配返回 null
     */
    public double[] predictPv(List<Map<String, Object>> points) {
        return predict("/predict", points);
    }

    /** 14 特征点批量预测负荷功率（键名与 Python LOAD_FEATURE_NAMES 对齐）。 */
    public double[] predictLoad(List<Map<String, Object>> points) {
        return predict("/predict_load", points);
    }

    private double[] predict(String path, List<Map<String, Object>> points) {
        if (!enabled || points == null || points.isEmpty()) {
            return null;
        }
        String body;
        try {
            body = transport.post(path, buildJson(points));
        } catch (IOException e) {
            return null;
        }
        if (body == null) {
            return null;
        }
        // 响应契约：{"code":200,"algorithm":"GBDT_PV_V1","predictKw":[1.23,4.56]}
        Matcher m = Pattern.compile("\"predictKw\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
        if (!m.find()) {
            return null;
        }
        String[] parts = m.group(1).split(",");
        if (parts.length != points.size()) {
            return null;   // 点数不匹配：特征顺序契约被破坏的显性症状之一
        }
        double[] result = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }

    /** 手工拼请求 JSON（教学版：键值均为数字/字符串常量，无嵌套）。 */
    static String buildJson(List<Map<String, Object>> points) {
        StringBuilder sb = new StringBuilder("{\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : points.get(i).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Number) {
                    sb.append(v);
                } else {
                    sb.append('"').append(v).append('"');
                }
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}

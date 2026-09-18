package com.openvpp.dispatch.evalloop;

import java.util.List;

/**
 * 控制反馈判定器 —— 专栏第 46 篇 ⑧ 效果评估第一步：先把「谁的锅」分清楚。
 *
 * 三条规则（每条都是防冤枉/防漏判）：
 * <ol>
 *   <li>参数对齐才算成功：下发「设定 26℃」回了「成功」但参数对不上，不算；
 *       数值容差 0.6，无数字时看关键词；</li>
 *   <li>通信异常不进分母：超时/断连/离线归 abnormal——网络问题不惩罚资源，
 *       否则一次网络抖动就把资源可达性打穿；</li>
 *   <li>2 分钟窗口 + 延迟追认：同设备同参数的后续记录若在窗口内成功且参数
 *       对齐，追认前一条为成功——设备回执天然滞后，硬判会冤枉人。</li>
 * </ol>
 */
public class ControlFeedbackAnalyzer {

    /** 延迟追认/参数对齐窗口（分钟） */
    public static final int MATCH_WINDOW_MINUTES = 2;

    /**
     * @param logs 任务控制日志（按时间升序）
     * @return 反馈统计（issued/abnormal/valid/success/successRate）
     */
    public FeedbackStats analyze(List<ControlLog> logs) {
        int issued = 0;
        int abnormal = 0;
        int valid = 0;
        int success = 0;
        for (int i = 0; i < logs.size(); i++) {
            ControlLog log = logs.get(i);
            issued++;
            if (isAbnormal(log.resultText())) {
                abnormal++;
                continue;
            }
            valid++;
            if (isSuccessNow(log) || isLateConfirmed(logs, i)) {
                success++;
            }
        }
        return new FeedbackStats(issued, abnormal, valid, success);
    }

    private static boolean isAbnormal(String text) {
        String t = text == null ? "" : text;
        return t.contains("通信") || t.contains("comm") || t.contains("timeout")
                || t.contains("断连") || t.contains("无应答") || t.contains("离线")
                || t.contains("下发异常") || t.contains("超时") || t.contains("系统失败");
    }

    private static boolean isSuccessNow(ControlLog log) {
        if (!"SUCCESS".equals(log.result())) {
            return false;
        }
        return paramsAligned(log.action(), log.actualValue());
    }

    /** 延迟追认：同 objectKey 后续记录在窗口内成功且参数对齐。 */
    private boolean isLateConfirmed(List<ControlLog> logs, int index) {
        ControlLog log = logs.get(index);
        for (int j = index + 1; j < logs.size(); j++) {
            ControlLog later = logs.get(j);
            long gapMin = (later.epochMinute() - log.epochMinute());
            if (gapMin > MATCH_WINDOW_MINUTES) {
                break;
            }
            if (later.objectKey().equals(log.objectKey())
                    && "SUCCESS".equals(later.result())
                    && paramsAligned(later.action(), later.actualValue())) {
                return true;
            }
        }
        return false;
    }

    /** 参数对齐：字符串互含，或双方首个数字差 ≤ 0.6，无数字时不拒控即对齐。 */
    static boolean paramsAligned(String action, String actual) {
        String a = action == null ? "" : action;
        String v = actual == null ? "" : actual;
        if (a.contains(v) || v.contains(a)) {
            return true;
        }
        Double na = firstNumber(a);
        Double nv = firstNumber(v);
        if (na != null && nv != null) {
            return Math.abs(na - nv) <= 0.6;
        }
        String lower = v.toLowerCase();
        return !(lower.contains("fail") || v.contains("失败") || v.contains("拒控"));
    }

    private static Double firstNumber(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d+(\\.\\d+)?").matcher(s);
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    /** 单条控制日志（教学版字段）。 */
    public static final class ControlLog {
        private final long epochMinute;
        private final String objectKey;
        private final String result;       // SUCCESS / FAIL / PENDING
        private final String resultText;   // 原始回执文本（判 abnormal 用）
        private final String action;       // 下发动作（如 set_temp=26.0）
        private final String actualValue;  // 设备实际值（如 26.2）

        public ControlLog(long epochMinute, String objectKey, String result,
                          String resultText, String action, String actualValue) {
            this.epochMinute = epochMinute;
            this.objectKey = objectKey;
            this.result = result;
            this.resultText = resultText;
            this.action = action;
            this.actualValue = actualValue;
        }

        public long epochMinute() {
            return epochMinute;
        }

        public String objectKey() {
            return objectKey;
        }

        public String result() {
            return result;
        }

        public String resultText() {
            return resultText;
        }

        public String action() {
            return action;
        }

        public String actualValue() {
            return actualValue;
        }
    }

    /** 反馈统计：成功率 = success/valid（abnormal 不进分母）。 */
    public static final class FeedbackStats {
        private final int issued;
        private final int abnormal;
        private final int valid;
        private final int success;

        FeedbackStats(int issued, int abnormal, int valid, int success) {
            this.issued = issued;
            this.abnormal = abnormal;
            this.valid = valid;
            this.success = success;
        }

        public int issued() {
            return issued;
        }

        public int abnormal() {
            return abnormal;
        }

        public int valid() {
            return valid;
        }

        public int success() {
            return success;
        }

        public double successRate() {
            return valid == 0 ? 0 : (double) success / valid;
        }
    }
}

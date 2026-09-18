package com.openvpp.dispatch.decompose;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 策略草稿构建器 —— 专栏第 46 篇「AI 出草稿，人拿决定权」。
 *
 * 把分解功率列翻译成可保存的策略命令：时间轴默认 ±15 分钟七档，
 * 空调类默认动作「模式=制冷、温度 26℃（动态判断）、条件 power=1」，
 * 其他类型功率置零。草稿落库后运营在页面上看得到、改得动。
 */
public class StrategyDraftBuilder {

    /** 默认时间轴（分钟，相对 T0；生产版可配置） */
    public static final int[] DEFAULT_TIME_STEPS = {-45, -30, -15, 0, 15, 30, 45};
    /** 空调类默认设定温度（动态判断基准） */
    public static final double AC_DEFAULT_SET_TEMP = 26.0;

    /**
     * @param allocations 资源级分配
     * @param slotPower   逐资源 15 分钟功率列
     * @return 策略草稿命令序列
     */
    public List<Command> build(DecomposeAlgoType algo,
                               List<CompositeAllocator.Allocation> allocations,
                               Map<String, double[]> slotPower) {
        List<Command> draft = new ArrayList<>();
        for (CompositeAllocator.Allocation a : allocations) {
            if (a.targetAdjustKwh() <= 0) {
                continue;   // 无容量的资源分量为 0，不参与分配也不出草稿
            }
            double[] slots = slotPower.getOrDefault(a.resourceId(), new double[0]);
            double avgKw = average(slots);
            boolean acLike = avgKw > 0 && avgKw < 5000;   // 教学简化：按量级判空调类
            if (acLike) {
                draft.add(new Command(a.resourceId(), 0, "mode=COOL; set_temp="
                        + AC_DEFAULT_SET_TEMP + " (dynamic)", "power=1"));
            } else {
                draft.add(new Command(a.resourceId(), 0,
                        "power_set=0kW; avg=" + Math.round(avgKw) + "kW", "always"));
            }
        }
        return draft;
    }

    private static double average(double[] slots) {
        if (slots.length == 0) {
            return 0;
        }
        double s = 0;
        for (double v : slots) {
            s += v;
        }
        return s / slots.length;
    }

    /** 单条策略草稿命令（教学版：时间步 + 动作 + 条件）。 */
    public static final class Command {
        private final String resourceId;
        private final int timeStepMin;
        private final String action;
        private final String condition;

        Command(String resourceId, int timeStepMin, String action, String condition) {
            this.resourceId = resourceId;
            this.timeStepMin = timeStepMin;
            this.action = action;
            this.condition = condition;
        }

        public String resourceId() {
            return resourceId;
        }

        public int timeStepMin() {
            return timeStepMin;
        }

        public String action() {
            return action;
        }

        public String condition() {
            return condition;
        }
    }
}

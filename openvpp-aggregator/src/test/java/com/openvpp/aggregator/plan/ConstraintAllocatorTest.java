package com.openvpp.aggregator.plan;

import com.openvpp.aggregator.plan.ConstraintAllocator.OperatorCapacity;
import com.openvpp.aggregator.plan.ConstraintAllocator.PileCapacity;
import com.openvpp.market.charge.DrDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 约束分配器单测（第 54 篇底座，对应验收格"派单可行性"主格）：
 * 逐级守恒、确定性尾差、缺口显性、零容量、削峰/填谷两方向换算、
 * 不超申报/可信/边界三重上限。
 */
class ConstraintAllocatorTest {

    private final ConstraintAllocator allocator = new ConstraintAllocator();

    private static void assertKw(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + "，实际 " + actual);
    }

    private static void assertKw(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + " —— 期望 " + expected + "，实际 " + actual);
    }

    private static OperatorCapacity op(String tenantId, String declared, String effective, String baseline) {
        return new OperatorCapacity(tenantId, new BigDecimal(declared), new BigDecimal(effective),
                new BigDecimal(baseline));
    }

    private static PileCapacity pile(String resourceId, String declared, String effective, String baseline) {
        return new PileCapacity(resourceId, "S-" + resourceId, "V1",
                new BigDecimal(declared), new BigDecimal(effective), new BigDecimal(baseline));
    }

    @Test
    void 一级分配按申报占比守恒() {
        // 两运营商各报 500 / 300，目标 800 → 全额恰好分完，缺口为 0
        AllocationDraft draft = allocator.allocateToOperators(new BigDecimal("800"), DrDirection.PEAK_SHAVE,
                List.of(op("T-1001", "500", "500", "240"), op("T-2001", "300", "300", "240")));

        assertTrue(draft.isFeasible());
        assertKw("800", draft.allocatedKw());
        assertEquals(2, draft.getLines().size());
        // 行按分配对象标识升序：T-1001 在前
        assertEquals("T-1001", draft.getLines().get(0).getSubjectId());
        assertKw("500", draft.getLines().get(0).getAdjustKw());
        assertKw("300", draft.getLines().get(1).getAdjustKw());
        assertNull(draft.getLines().get(0).getTargetPowerKw(), "一级行不得携带绝对目标功率");
    }

    @Test
    void 尾差按标识序确定性补发且两次分配结果一致() {
        // 目标 2 kW、三桩各 1 kW：占比 0.666…，取整后尾差 0.2 kW
        // 按资源标识升序补发 → 永远 P-1=0.7、P-2=0.7、P-3=0.6
        List<PileCapacity> piles = List.of(
                pile("P-3", "1", "1", "50"),
                pile("P-1", "1", "1", "50"),
                pile("P-2", "1", "1", "50"));

        AllocationDraft first = allocator.allocateToPiles(new BigDecimal("2"), DrDirection.PEAK_SHAVE, piles);
        AllocationDraft second = allocator.allocateToPiles(new BigDecimal("2"), DrDirection.PEAK_SHAVE, piles);

        assertEquals(first.getLines(), second.getLines(), "同输入必同输出（确定性）");
        assertTrue(first.isFeasible());
        assertKw("0.7", first.getLines().get(0).getAdjustKw());
        assertKw("0.7", first.getLines().get(1).getAdjustKw());
        assertKw("0.6", first.getLines().get(2).getAdjustKw());
        // 守恒：0.7 + 0.7 + 0.6 = 2
        assertKw("2", first.allocatedKw());
        // 削峰换算：目标 = 基线 − 调节量
        assertKw("49.3", first.getLines().get(0).getTargetPowerKw());
        assertKw("49.4", first.getLines().get(2).getTargetPowerKw());
    }

    @Test
    void 申报不足时取满上限并显性返回缺口() {
        // 目标 1000，可用合计 800 → 各行取满，缺口 200，不得抹平
        AllocationDraft draft = allocator.allocateToOperators(new BigDecimal("1000"), DrDirection.PEAK_SHAVE,
                List.of(op("T-1001", "500", "500", "240"), op("T-2001", "300", "300", "240")));

        assertFalse(draft.isFeasible());
        assertKw("200", draft.getGapKw());
        assertKw("800", draft.allocatedKw());
        assertKw("500", draft.getLines().get(0).getAdjustKw());
        assertKw("300", draft.getLines().get(1).getAdjustKw());
        // 守恒：部分分配成功 ≠ 总目标可兑现，缺口单列
        assertKw("1000", draft.allocatedKw().add(draft.getGapKw()));
    }

    @Test
    void 零容量主体分得零且剩余容量被其他主体吸收() {
        // P-zero 基线 0（削峰余量 0）→ 有效容量 0；目标 50 全落 P-ok
        List<PileCapacity> piles = List.of(
                pile("P-ok", "100", "100", "120"),
                pile("P-zero", "100", "0", "0"));

        AllocationDraft draft = allocator.allocateToPiles(new BigDecimal("50"), DrDirection.PEAK_SHAVE, piles);

        assertTrue(draft.isFeasible());
        assertEquals(2, draft.getLines().size());
        assertKw("50", draft.getLines().get(0).getAdjustKw(), "按标识序 P-ok 在前，目标全额落 P-ok");
        assertKw("0", draft.getLines().get(1).getAdjustKw(), "零容量主体分得零、留痕在册");
        // 目标换算：P-ok 削峰 120 − 50 = 70；P-zero 零调节回原状（基线 0 → 目标 0）
        assertKw("70", draft.getLines().get(0).getTargetPowerKw());
        assertKw("0", draft.getLines().get(1).getTargetPowerKw());
    }

    @Test
    void 各行不超申报可信与边界三重上限() {
        // P-1：申报 100 / 可信 80 → 有效 80；P-2：申报 50 → 有效 50（不超申报量）
        // 目标 130 = 80 + 50，恰好取满两条上限
        AllocationDraft draft = allocator.allocateToPiles(new BigDecimal("130"), DrDirection.PEAK_SHAVE,
                List.of(pile("P-1", "100", "80", "120"), pile("P-2", "50", "50", "120")));

        assertTrue(draft.isFeasible());
        assertKw("80", draft.getLines().get(0).getAdjustKw(), "不超方向可信容量");
        assertKw("50", draft.getLines().get(1).getAdjustKw(), "不超申报量");
    }

    @Test
    void 削峰换算为基线减调节量且以零为界() {
        AllocationDraft draft = allocator.allocateToPiles(new BigDecimal("120"), DrDirection.PEAK_SHAVE,
                List.of(pile("P-1", "120", "120", "120")));

        assertTrue(draft.isFeasible());
        assertKw("120", draft.getLines().get(0).getAdjustKw());
        assertKw("0", draft.getLines().get(0).getTargetPowerKw(), "削峰压到停机：目标 0 合法");
    }

    @Test
    void 填谷换算为基线加调节量且以额为界() {
        // 额定 150、基线 120 → 填谷余量 30：申报 80 也被边界裁到 30
        PileCapacity bounded = new PileCapacity("P-b", "S-P-b", "V1",
                new BigDecimal("80"), new BigDecimal("30"), new BigDecimal("120"));
        AllocationDraft draft = allocator.allocateToPiles(new BigDecimal("30"), DrDirection.VALLEY_FILL,
                List.of(bounded));

        assertTrue(draft.isFeasible());
        assertKw("30", draft.getLines().get(0).getAdjustKw());
        assertKw("150", draft.getLines().get(0).getTargetPowerKw(), "填谷抬升：基线 + 调节量 = 额定");
    }

    @Test
    void 填谷方向基线加调节量() {
        AllocationDraft draft = allocator.allocateToPiles(new BigDecimal("40"), DrDirection.VALLEY_FILL,
                List.of(pile("P-1", "40", "40", "120")));

        assertKw("160", draft.getLines().get(0).getTargetPowerKw(), "填谷 = 基线 + 调节量");
    }

    @Test
    void 空候选清单全额缺口() {
        AllocationDraft draft = allocator.allocateToOperators(new BigDecimal("800"), DrDirection.PEAK_SHAVE,
                List.of());

        assertFalse(draft.isFeasible());
        assertKw("800", draft.getGapKw());
        assertTrue(draft.getLines().isEmpty());
    }
}

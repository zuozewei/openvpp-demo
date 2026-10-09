package com.openvpp.aggregator.plan;

import com.openvpp.market.charge.DrDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 方向性目标换算器单测（第 54 篇底座）：削峰减、填谷加两方向分列，
 * 零调节回原状（目标=基线），负目标硬拒绝（口径错误，非零目标）。
 */
class DirectionalTargetConverterTest {

    private static void assertKw(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + "，实际 " + actual);
    }

    @Test
    void 削峰为基线减调节量() {
        assertKw("70", DirectionalTargetConverter.toTargetPower(
                DrDirection.PEAK_SHAVE, new BigDecimal("120"), new BigDecimal("50")));
    }

    @Test
    void 削峰压到零目标合法() {
        assertKw("0", DirectionalTargetConverter.toTargetPower(
                DrDirection.PEAK_SHAVE, new BigDecimal("120"), new BigDecimal("120")));
    }

    @Test
    void 填谷为基线加调节量() {
        assertKw("170", DirectionalTargetConverter.toTargetPower(
                DrDirection.VALLEY_FILL, new BigDecimal("120"), new BigDecimal("50")));
    }

    @Test
    void 零调节即回原状目标等于基线() {
        assertKw("120", DirectionalTargetConverter.toTargetPower(
                DrDirection.PEAK_SHAVE, new BigDecimal("120"), BigDecimal.ZERO));
        assertKw("120", DirectionalTargetConverter.toTargetPower(
                DrDirection.VALLEY_FILL, new BigDecimal("120"), BigDecimal.ZERO));
    }

    @Test
    void 削峰调节量超过基线产生负目标硬拒绝() {
        IllegalStateException negative = assertThrows(IllegalStateException.class,
                () -> DirectionalTargetConverter.toTargetPower(
                        DrDirection.PEAK_SHAVE, new BigDecimal("120"), new BigDecimal("121")));
        assertTrue(negative.getMessage().contains("负目标"));
    }

    @Test
    void 非法输入拒绝() {
        assertThrows(NullPointerException.class, () -> DirectionalTargetConverter.toTargetPower(
                null, new BigDecimal("120"), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DirectionalTargetConverter.toTargetPower(
                DrDirection.PEAK_SHAVE, new BigDecimal("-1"), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DirectionalTargetConverter.toTargetPower(
                DrDirection.PEAK_SHAVE, new BigDecimal("120"), new BigDecimal("-1")));
    }
}

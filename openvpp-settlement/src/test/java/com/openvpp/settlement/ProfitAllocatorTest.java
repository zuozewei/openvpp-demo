package com.openvpp.settlement;

import com.openvpp.settlement.allocation.ProfitAllocator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收益分摊单测：保底按容量 / 分成按贡献 / 平台服务费分位计提 / 精确守恒 / 输入契约与边界拒绝。
 */
class ProfitAllocatorTest {

    private final ProfitAllocator allocator = new ProfitAllocator(new BigDecimal("0.10"));

    @Test
    void 保底分成两层分配且总和精确守恒() {
        // 平台实收 1380 元，保底 600 元，分成池 780 元（服务费 10% 计提到分位后 78.00，可分 702）
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("user-A", new BigDecimal("300"));
        declared.put("user-B", new BigDecimal("200"));

        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("user-A", new BigDecimal("276"));   // 贡献 60%
        actual.put("user-B", new BigDecimal("184"));   // 贡献 40%

        Map<String, BigDecimal> result = allocator.allocate(
                new BigDecimal("1380"), new BigDecimal("600"), declared, actual);

        assertEquals(0, result.get("user-A").compareTo(new BigDecimal("781.20")), "A 保底360+分成421.20");
        assertEquals(0, result.get("user-B").compareTo(new BigDecimal("520.80")), "B 保底240+分成280.80");
        BigDecimal sum = result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // 精确守恒：用户合计 1302.00 + 服务费 78.00 = 实收 1380.00，分毫不差（无 1 分容差）
        assertEquals(0, sum.compareTo(new BigDecimal("1302.00")), "分配总和须精确守恒: " + sum);
    }

    @Test
    void 平台服务费从分成部分分位计提() {
        ProfitAllocator noCut = new ProfitAllocator(BigDecimal.ZERO);
        Map<String, BigDecimal> declared = Map.of("u1", new BigDecimal("100"));
        Map<String, BigDecimal> actual = Map.of("u1", new BigDecimal("100"));

        Map<String, BigDecimal> withCut = allocator.allocate(
                new BigDecimal("1000"), new BigDecimal("400"), declared, actual);
        Map<String, BigDecimal> without = noCut.allocate(
                new BigDecimal("1000"), new BigDecimal("400"), declared, actual);

        assertTrue(withCut.get("u1").compareTo(without.get("u1")) < 0,
                "计提服务费后用户所得应减少");
        // 服务费 10%：差额 = 分成池 600 × 10% = 60
        assertEquals(0, without.get("u1").subtract(withCut.get("u1"))
                .compareTo(new BigDecimal("60.00")), "服务费金额口径错误");
    }

    @Test
    void 贡献为零的用户只拿保底() {
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("active", new BigDecimal("100"));
        declared.put("dormant", new BigDecimal("100"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("active", new BigDecimal("100"));
        actual.put("dormant", BigDecimal.ZERO);

        Map<String, BigDecimal> result = allocator.allocate(
                new BigDecimal("800"), new BigDecimal("400"), declared, actual);

        assertEquals(0, result.get("dormant").compareTo(new BigDecimal("200.00")),
                "零贡献用户只应得保底份额");
    }

    @Test
    void 含分位实收时守恒到分位() {
        // 实收 1200.01 元：服务费 = 600.01 × 10% = 60.001 → 分位 60.00；可分 540.01
        // A 分成 540.01 × 600/900 = 360.006 → 向下 360.00，残差 0.01 归并 A
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", new BigDecimal("300"));
        declared.put("B", new BigDecimal("200"));
        declared.put("C", new BigDecimal("100"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", new BigDecimal("600"));
        actual.put("B", new BigDecimal("300"));
        actual.put("C", BigDecimal.ZERO);

        Map<String, BigDecimal> result = allocator.allocate(
                new BigDecimal("1200.01"), new BigDecimal("600.00"), declared, actual);

        assertEquals(0, result.get("A").compareTo(new BigDecimal("660.01")), "A=保底300+分成360+残差0.01");
        assertEquals(0, result.get("B").compareTo(new BigDecimal("380.00")), "B=保底200+分成180");
        assertEquals(0, result.get("C").compareTo(new BigDecimal("100.00")), "零贡献只拿保底");
        BigDecimal sum = result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // 1140.01 + 服务费 60.00 = 1200.01，分位精确守恒
        assertEquals(0, sum.compareTo(new BigDecimal("1140.01")), "含分位实收须守恒到分位: " + sum);
    }

    @Test
    void 全员零贡献拒绝普通分摊() {
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", new BigDecimal("300"));
        declared.put("B", new BigDecimal("200"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", BigDecimal.ZERO);
        actual.put("B", BigDecimal.ZERO);

        // 分成池无从按贡献分配，整池留待异常处置（结转/人工），不得当作舍入尾差分给任何人
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200"), new BigDecimal("600"), declared, actual),
                "全员零贡献应拒绝普通分摊");
    }

    @Test
    void 保底超过实收拒绝普通分摊() {
        Map<String, BigDecimal> declared = Map.of("A", new BigDecimal("100"));
        Map<String, BigDecimal> actual = Map.of("A", new BigDecimal("100"));

        // 实收 300 不足以覆盖保底 600：差额属平台垫资/合同处置，不是本分摊器的资金来源
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("300"), new BigDecimal("600"), declared, actual),
                "保底超过实收应拒绝普通分摊");
    }

    @Test
    void 申报与响应主体集合不一致拒绝() {
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", new BigDecimal("300"));
        declared.put("B", new BigDecimal("200"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", new BigDecimal("600"));

        // 缺 B 的响应时 B 的保底会被并给他人，须在入口拒绝
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200"), new BigDecimal("600"), declared, actual),
                "主体集合不一致应拒绝");
    }

    @Test
    void 申报容量总和为零拒绝() {
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", BigDecimal.ZERO);
        declared.put("B", BigDecimal.ZERO);
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", new BigDecimal("1"));
        actual.put("B", BigDecimal.ZERO);

        // 主体集合一致，先过键集校验、再命中容量守卫本身
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200"), new BigDecimal("600"), declared, actual),
                "容量总和为零（除零）应拒绝");
        assertTrue(ex.getMessage().contains("申报容量"), "应命中容量守卫: " + ex.getMessage());
    }

    @Test
    void 服务费率与金额精度契约拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new ProfitAllocator(BigDecimal.ONE),
                "费率 1 越界");
        assertThrows(IllegalArgumentException.class, () -> new ProfitAllocator(new BigDecimal("-0.1")),
                "负费率越界");

        Map<String, BigDecimal> declared = Map.of("A", new BigDecimal("100"));
        Map<String, BigDecimal> actual = Map.of("A", new BigDecimal("100"));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200.015"), new BigDecimal("600"), declared, actual),
                "超过分位精度的实收应拒绝");
    }

    @Test
    void 半分临界服务费按半入计提() {
        // 实收 1200.05：服务费 600.05 × 10% = 60.005 → 半入 60.01（与正文 Python ROUND_HALF_UP 同口径）
        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", new BigDecimal("300"));
        declared.put("B", new BigDecimal("200"));
        declared.put("C", new BigDecimal("100"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", new BigDecimal("600"));
        actual.put("B", new BigDecimal("300"));
        actual.put("C", BigDecimal.ZERO);

        Map<String, BigDecimal> result = allocator.allocate(
                new BigDecimal("1200.05"), new BigDecimal("600.00"), declared, actual);

        assertEquals(0, result.get("A").compareTo(new BigDecimal("660.03")), "A=保底300+分成360.02+残差0.01");
        assertEquals(0, result.get("B").compareTo(new BigDecimal("380.01")), "B=保底200+分成180.01");
        assertEquals(0, result.get("C").compareTo(new BigDecimal("100.00")), "零贡献只拿保底");
        BigDecimal sum = result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // 1140.04 + 服务费 60.01 = 1200.05，半分边界与正文口径一致
        assertEquals(0, sum.compareTo(new BigDecimal("1140.04")), "半分临界须守恒到分位: " + sum);
    }

    @Test
    void 单户负容量或负响应拒绝() {
        Map<String, BigDecimal> badDeclared = new LinkedHashMap<>();
        badDeclared.put("A", new BigDecimal("-100"));
        badDeclared.put("B", new BigDecimal("200"));
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        actual.put("A", new BigDecimal("100"));
        actual.put("B", new BigDecimal("100"));

        // 负容量会产生反向账单，拒绝普通分摊；负考核须另立扣款科目，不作贡献权重
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200"), new BigDecimal("600"), badDeclared, actual),
                "负申报容量应拒绝");
        assertTrue(ex.getMessage().contains("申报容量"), ex.getMessage());

        Map<String, BigDecimal> declared = new LinkedHashMap<>();
        declared.put("A", new BigDecimal("300"));
        declared.put("B", new BigDecimal("200"));
        declared.put("C", new BigDecimal("100"));
        Map<String, BigDecimal> badActual = new LinkedHashMap<>();
        badActual.put("A", new BigDecimal("-100"));
        badActual.put("B", new BigDecimal("200"));
        badActual.put("C", BigDecimal.ZERO);

        ex = assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(new BigDecimal("1200"), new BigDecimal("600"), declared, badActual),
                "负响应量应拒绝");
        assertTrue(ex.getMessage().contains("响应量"), ex.getMessage());
    }
}

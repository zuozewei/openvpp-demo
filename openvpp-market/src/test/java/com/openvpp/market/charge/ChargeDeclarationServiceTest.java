package com.openvpp.market.charge;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 申报服务单测（第 53 篇底座，对应验收矩阵"申报正确性"行）：
 * 截止前/时/后边界 / 超容量拒绝 / 幂等重复提交 / 撤回与拒绝释放 /
 * 快照过期与会话变化 / 参与授权 / 数据范围越权拒绝 / 按范围查询。
 */
class ChargeDeclarationServiceTest {

    private static final LocalDateTime CREATE_AT = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 8, 13, 30);
    private static final LocalDateTime DECLARE_AT = LocalDateTime.of(2026, 10, 8, 13, 0);
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 8, 14, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 8, 16, 0);
    private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.of(2026, 10, 8, 12, 50);

    private SnapshotRegistry snapshotRegistry;
    private CapacityOccupancyLedger ledger;
    private ChargeDrEventService eventService;
    private ChargeDeclarationService service;

    @BeforeEach
    void setUp() {
        snapshotRegistry = new SnapshotRegistry(Duration.ofMinutes(30));
        ledger = new CapacityOccupancyLedger();
        eventService = new ChargeDrEventService();
        service = new ChargeDeclarationService(eventService, snapshotRegistry, ledger);
    }

    private static DataScope platformScope(String tenantId) {
        return DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", tenantId, RoleType.PLATFORM_ADMIN, Set.of()));
    }

    private static DataScope operatorScope(String tenantId, String... stationIds) {
        return DataScopeResolver.resolve(
                IdentityContext.of("operator-acc", tenantId, RoleType.OPERATOR, Set.of(stationIds)));
    }

    private static DataScope stationScope(String tenantId, String... stationIds) {
        return DataScopeResolver.resolve(
                IdentityContext.of("station-acc", tenantId, RoleType.STATION_OPERATOR, Set.of(stationIds)));
    }

    private static DataScope emptyScope(String tenantId) {
        return DataScopeResolver.resolve(
                IdentityContext.of("station-acc-none", tenantId, RoleType.STATION_OPERATOR, Set.of()));
    }

    private void publishEvent(String eventId, DrDirection direction, Collection<String> stationIds) {
        eventService.create(eventId, "T-1001", direction,
                WINDOW_START, WINDOW_END, new BigDecimal("800"), DEADLINE, CREATE_AT);
        eventService.publish(platformScope("T-1001"), eventId, CREATE_AT.plusMinutes(5));
        if (!stationIds.isEmpty()) {
            eventService.authorizeParticipation(platformScope("T-1001"), eventId,
                    stationIds, "platform-acc", CREATE_AT.plusMinutes(10));
        }
    }

    /** 登记默认快照：额定 240 / 基线 120 / 削峰可信 100 / 填谷可信 80 */
    private void registerSnapshot(String stationId, String version, String fingerprint, LocalDateTime assessedAt) {
        snapshotRegistry.register(new CapabilitySnapshot(stationId, "ev-charge-" + stationId.toLowerCase(),
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                version, assessedAt, fingerprint));
    }

    private void prepareDefaultEventAndSnapshot() {
        publishEvent("EV-5401", DrDirection.PEAK_SHAVE, List.of("S-11101"));
        registerSnapshot("S-11101", "V1", "fp-A", SNAPSHOT_AT);
    }

    private ChargeDeclaration declareDefault(String requestId, BigDecimal kw, LocalDateTime at) {
        return service.declare(stationScope("T-1001", "S-11101"), "EV-5401", "S-11101", "T-1001",
                kw, "V1", requestId, at);
    }

    @Test
    void 截止前申报成功并生成容量占用() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration declaration = declareDefault("REQ-001", new BigDecimal("60"), DECLARE_AT);

        assertEquals(DeclarationStatus.SUBMITTED, declaration.getStatus());
        assertEquals("EV-5401", declaration.getEventId());
        assertEquals("T-1001", declaration.getTenantId());
        assertEquals("S-11101", declaration.getStationId());
        assertEquals("V1", declaration.getSnapshotVersion());
        assertEquals(new BigDecimal("60"), ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END));
        assertEquals(1, ledger.recordsOf("S-11101").size());
        assertEquals(CapacityOccupancyLedger.Action.OCCUPY, ledger.recordsOf("S-11101").get(0).getAction());
    }

    @Test
    void 截止时刻与截止后申报被拒绝() {
        prepareDefaultEventAndSnapshot();
        // 截止时刻本身即拒绝（截止时刻起关闭）
        IllegalStateException atDeadline = assertThrows(IllegalStateException.class,
                () -> declareDefault("REQ-002", new BigDecimal("60"), DEADLINE));
        assertTrue(atDeadline.getMessage().contains("截止"));
        // 截止后同样拒绝
        assertThrows(IllegalStateException.class,
                () -> declareDefault("REQ-003", new BigDecimal("60"), DEADLINE.plusSeconds(1)));
        // 两笔拒绝都未产生占用
        assertEquals(0, ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END).signum());
    }

    @Test
    void 未发布与已结束事件拒绝申报() {
        prepareDefaultEventAndSnapshot();
        eventService.create("EV-5402", "T-1001", DrDirection.PEAK_SHAVE,
                WINDOW_START, WINDOW_END, new BigDecimal("800"), DEADLINE, CREATE_AT);
        assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5402", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-004", DECLARE_AT));

        publishEvent("EV-5403", DrDirection.PEAK_SHAVE, List.of("S-11101"));
        eventService.end(platformScope("T-1001"), "EV-5403", DECLARE_AT);
        assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5403", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-005", DECLARE_AT));
    }

    @Test
    void 未获参与授权的场站拒绝申报() {
        publishEvent("EV-5404", DrDirection.PEAK_SHAVE, List.of("S-11201"));
        registerSnapshot("S-11101", "V1", "fp-A", SNAPSHOT_AT);

        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5404", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-006", DECLARE_AT));
        assertTrue(rejected.getMessage().contains("参与授权"));
    }

    @Test
    void 数据范围守卫拒绝越权申报() {
        prepareDefaultEventAndSnapshot();
        // 空范围：禁止退化为全量操作
        assertThrows(IllegalStateException.class, () -> service.declare(
                emptyScope("T-1001"), "EV-5401", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-007", DECLARE_AT));
        // 其他租户平台范围：租户锚定不符
        assertThrows(IllegalStateException.class, () -> service.declare(
                platformScope("T-2001"), "EV-5401", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-008", DECLARE_AT));
        // 场站清单不含目标场站
        assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-99999"), "EV-5401", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-009", DECLARE_AT));
        // 租户归属声明与平台范围锚定一致时才允许（平台代录口径）
        ChargeDeclaration declared = service.declare(platformScope("T-1001"), "EV-5401", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-010", DECLARE_AT);
        assertEquals(DeclarationStatus.SUBMITTED, declared.getStatus());
    }

    @Test
    void 超可信容量拒绝且恰好等于可信容量通过() {
        prepareDefaultEventAndSnapshot();
        // 削峰事件：可信容量列取 crediblePeakKw=100
        IllegalStateException over = assertThrows(IllegalStateException.class,
                () -> declareDefault("REQ-011", new BigDecimal("100.1"), DECLARE_AT));
        assertTrue(over.getMessage().contains("可信容量"));

        ChargeDeclaration atLimit = declareDefault("REQ-012", new BigDecimal("100"), DECLARE_AT);
        assertEquals(DeclarationStatus.SUBMITTED, atLimit.getStatus());
        // 余量为 0，再来 1 kW 也被预占守门拒绝
        IllegalStateException exhausted = assertThrows(IllegalStateException.class,
                () -> declareDefault("REQ-013", BigDecimal.ONE, DECLARE_AT));
        assertTrue(exhausted.getMessage().contains("预占不足"));
    }

    @Test
    void 填谷事件取填谷可信容量列() {
        publishEvent("EV-5405", DrDirection.VALLEY_FILL, List.of("S-11101"));
        registerSnapshot("S-11101", "V1", "fp-A", SNAPSHOT_AT);
        // 填谷可信容量 80：81 拒绝、80 通过
        assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5405", "S-11101", "T-1001",
                new BigDecimal("81"), "V1", "REQ-014", DECLARE_AT));
        assertDoesNotThrow(() -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5405", "S-11101", "T-1001",
                new BigDecimal("80"), "V1", "REQ-015", DECLARE_AT));
    }

    @Test
    void 重复请求标识幂等不重复占用() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration first = declareDefault("REQ-016", new BigDecimal("60"), DECLARE_AT);
        // 同请求标识重复提交（容量参数不同也返回原单）：不重复校验、不重复占用
        ChargeDeclaration retry = declareDefault("REQ-016", new BigDecimal("90"), DECLARE_AT);

        assertEquals(first.getDeclarationId(), retry.getDeclarationId());
        assertEquals(DeclarationStatus.SUBMITTED, retry.getStatus());
        assertEquals(new BigDecimal("60"), retry.getDeclaredKw(), "幂等命中返回原申报容量");
        assertEquals(new BigDecimal("60"), ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END),
                "重复请求不得重复占用");
        assertEquals(1, service.queryDeclarations(platformScope("T-1001")).size());
    }

    @Test
    void 确认进入已确认态且终态不可逆() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration declaration = declareDefault("REQ-017", new BigDecimal("60"), DECLARE_AT);

        ChargeDeclaration confirmed = service.confirm(operatorScope("T-1001", "S-11101"),
                declaration.getDeclarationId(), DECLARE_AT);
        assertEquals(DeclarationStatus.CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getDecidedAt());
        // 已确认不能再确认 / 拒绝
        assertThrows(IllegalStateException.class, () -> service.confirm(
                operatorScope("T-1001", "S-11101"), declaration.getDeclarationId(), DECLARE_AT));
        assertThrows(IllegalStateException.class, () -> service.reject(
                operatorScope("T-1001", "S-11101"), declaration.getDeclarationId(), "改主意", DECLARE_AT));
        // 越权确认：场站清单不含本场站
        assertThrows(IllegalStateException.class, () -> service.confirm(
                operatorScope("T-1001", "S-99999"), declaration.getDeclarationId(), DECLARE_AT));
        // 确认不释放占用
        assertEquals(new BigDecimal("60"), ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END));
    }

    @Test
    void 拒绝释放占用并留痕() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration declaration = declareDefault("REQ-018", new BigDecimal("60"), DECLARE_AT);

        ChargeDeclaration rejected = service.reject(operatorScope("T-1001", "S-11101"),
                declaration.getDeclarationId(), "容量与计划不符", DECLARE_AT);
        assertEquals(DeclarationStatus.REJECTED, rejected.getStatus());
        assertEquals("容量与计划不符", rejected.getDecisionReason());
        assertEquals(0, ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END).signum(), "拒绝必须释放占用");

        List<CapacityOccupancyLedger.OccupancyRecord> records = ledger.recordsOf("S-11101");
        assertEquals(2, records.size());
        assertEquals(CapacityOccupancyLedger.Action.OCCUPY, records.get(0).getAction());
        assertEquals(CapacityOccupancyLedger.Action.RELEASE, records.get(1).getAction());
        assertEquals(new BigDecimal("60"), records.get(1).getKw());

        // 终态拒绝再迁移
        assertThrows(IllegalStateException.class, () -> service.withdraw(
                stationScope("T-1001", "S-11101"), declaration.getDeclarationId(), DECLARE_AT));
    }

    @Test
    void 撤回释放占用并可重新申报() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration declaration = declareDefault("REQ-019", new BigDecimal("60"), DECLARE_AT);

        ChargeDeclaration withdrawn = service.withdraw(stationScope("T-1001", "S-11101"),
                declaration.getDeclarationId(), DECLARE_AT);
        assertEquals(DeclarationStatus.WITHDRAWN, withdrawn.getStatus());
        assertEquals(0, ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END).signum(), "撤回必须释放占用");

        // 释放后可重新申报（新请求标识）
        ChargeDeclaration redeclared = declareDefault("REQ-020", new BigDecimal("70"), DECLARE_AT);
        assertEquals(DeclarationStatus.SUBMITTED, redeclared.getStatus());
        assertEquals(new BigDecimal("70"), ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END));

        // 终态不可再撤回
        assertThrows(IllegalStateException.class, () -> service.withdraw(
                stationScope("T-1001", "S-11101"), declaration.getDeclarationId(), DECLARE_AT));
    }

    @Test
    void 已确认申报撤回同样释放占用() {
        prepareDefaultEventAndSnapshot();
        ChargeDeclaration declaration = declareDefault("REQ-021", new BigDecimal("60"), DECLARE_AT);
        service.confirm(operatorScope("T-1001", "S-11101"), declaration.getDeclarationId(), DECLARE_AT);

        ChargeDeclaration withdrawn = service.withdraw(stationScope("T-1001", "S-11101"),
                declaration.getDeclarationId(), DECLARE_AT);
        assertEquals(DeclarationStatus.WITHDRAWN, withdrawn.getStatus());
        assertEquals(0, ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END).signum());
    }

    @Test
    void 快照过期拒绝申报() {
        publishEvent("EV-5406", DrDirection.PEAK_SHAVE, List.of("S-11101"));
        // 12:00 评估 + 30 分钟有效期 = 12:30 失效，13:00 申报必然过期
        registerSnapshot("S-11101", "V1", "fp-A", LocalDateTime.of(2026, 10, 8, 12, 0));

        IllegalStateException expired = assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5406", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-022", DECLARE_AT));
        assertTrue(expired.getMessage().contains("过期"));
    }

    @Test
    void 充电会话变化后旧快照版本拒绝新版本可用() {
        publishEvent("EV-5407", DrDirection.PEAK_SHAVE, List.of("S-11101"));
        registerSnapshot("S-11101", "V1", "fp-A", SNAPSHOT_AT);
        // 13:05 车辆进出变化，登记指纹变化的新版本
        registerSnapshot("S-11101", "V2", "fp-B", LocalDateTime.of(2026, 10, 8, 13, 5));

        // 旧版本 V1（指纹 fp-A）已随会话变化失效
        IllegalStateException stale = assertThrows(IllegalStateException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5407", "S-11101", "T-1001",
                new BigDecimal("60"), "V1", "REQ-023", LocalDateTime.of(2026, 10, 8, 13, 10)));
        assertTrue(stale.getMessage().contains("会话已变化"));

        // 新版本 V2 正常受理（未过期、指纹为当前值）
        assertDoesNotThrow(() -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5407", "S-11101", "T-1001",
                new BigDecimal("60"), "V2", "REQ-024", LocalDateTime.of(2026, 10, 8, 13, 10)));
    }

    @Test
    void 未登记快照版本拒绝申报() {
        publishEvent("EV-5408", DrDirection.PEAK_SHAVE, List.of("S-11101"));
        assertThrows(IllegalArgumentException.class, () -> service.declare(
                stationScope("T-1001", "S-11101"), "EV-5408", "S-11101", "T-1001",
                new BigDecimal("60"), "VX", "REQ-025", DECLARE_AT));
    }

    @Test
    void 申报查询按数据范围隔离与短路() {
        prepareDefaultEventAndSnapshot();
        publishEvent("EV-5409", DrDirection.PEAK_SHAVE, List.of("S-11201"));
        registerSnapshot("S-11201", "V1", "fp-A", SNAPSHOT_AT);

        ChargeDeclaration d1 = declareDefault("REQ-026", new BigDecimal("60"), DECLARE_AT);
        ChargeDeclaration d2 = service.declare(stationScope("T-1001", "S-11201"), "EV-5409", "S-11201", "T-1001",
                new BigDecimal("50"), "V1", "REQ-027", DECLARE_AT);

        // 平台（tenantWide）见本租户全部申报
        assertEquals(Set.of(d1.getDeclarationId(), d2.getDeclarationId()),
                service.queryDeclarations(platformScope("T-1001")).stream()
                        .map(ChargeDeclaration::getDeclarationId).collect(Collectors.toSet()));
        // 场站仅见本家
        assertEquals(List.of(d1.getDeclarationId()), service.queryDeclarations(stationScope("T-1001", "S-11101"))
                .stream().map(ChargeDeclaration::getDeclarationId).collect(Collectors.toList()));
        // 运营商多场站清单见两单
        assertEquals(2, service.queryDeclarations(operatorScope("T-1001", "S-11101", "S-11201")).size());
        // 其他租户不可见
        assertTrue(service.queryDeclarations(operatorScope("T-2001", "S-21101")).isEmpty());
        // 空范围短路
        assertTrue(service.queryDeclarations(emptyScope("T-1001")).isEmpty());
    }
}

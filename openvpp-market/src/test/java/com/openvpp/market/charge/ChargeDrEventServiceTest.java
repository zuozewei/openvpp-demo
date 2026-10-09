package com.openvpp.market.charge;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件服务单测（第 53 篇底座）：建立时间链 / 时间状态推导 / 生命周期 /
 * 参与授权与版本 / 组织方范围守卫 / 按数据范围查询。
 */
class ChargeDrEventServiceTest {

    private static final LocalDateTime CREATE_AT = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 8, 13, 30);
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 8, 14, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 8, 16, 0);

    private ChargeDrEventService service;

    @BeforeEach
    void setUp() {
        service = new ChargeDrEventService();
    }

    private static DataScope platformScope(String tenantId) {
        return DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", tenantId, RoleType.PLATFORM_ADMIN, Set.of()));
    }

    private static DataScope operatorScope(String tenantId, String... stationIds) {
        return DataScopeResolver.resolve(
                IdentityContext.of("operator-acc", tenantId, RoleType.OPERATOR, Set.of(stationIds)));
    }

    private static DataScope emptyScope(String tenantId) {
        return DataScopeResolver.resolve(
                IdentityContext.of("station-acc-none", tenantId, RoleType.STATION_OPERATOR, Set.of()));
    }

    private ChargeDrEvent createDefault(String eventId) {
        return service.create(eventId, "T-1001", DrDirection.PEAK_SHAVE,
                WINDOW_START, WINDOW_END, new BigDecimal("800"), DEADLINE, CREATE_AT);
    }

    @Test
    void 建立发布事件成功且时间要素齐备() {
        ChargeDrEvent created = createDefault("EV-5301");
        assertEquals(EventLifecycle.CREATED, created.getLifecycle());
        assertEquals(1, created.getVersion());
        assertEquals("T-1001", created.getOrganizerTenantId());
        assertEquals(DrDirection.PEAK_SHAVE, created.getDirection());
        assertEquals(DEADLINE, created.getDeclareDeadline());

        ChargeDrEvent published = service.publish(platformScope("T-1001"), "EV-5301", CREATE_AT.plusMinutes(5));
        assertEquals(EventLifecycle.PUBLISHED, published.getLifecycle());
        assertEquals(new BigDecimal("800"), published.getTargetAdjustKw());
    }

    @Test
    void 时间状态按响应窗口推导含边界() {
        ChargeDrEvent event = createDefault("EV-5302");
        assertEquals(EventTimeStatus.PENDING, event.timeStatusAt(WINDOW_START.minusNanos(1)));
        assertEquals(EventTimeStatus.PENDING, event.timeStatusAt(LocalDateTime.of(2026, 10, 8, 13, 59, 59)));
        assertEquals(EventTimeStatus.RESPONDING, event.timeStatusAt(WINDOW_START), "到达窗口起点即响应中");
        assertEquals(EventTimeStatus.RESPONDING, event.timeStatusAt(WINDOW_END.minusNanos(1)));
        assertEquals(EventTimeStatus.ENDED, event.timeStatusAt(WINDOW_END), "到达窗口终点即结束");
        assertEquals(EventTimeStatus.ENDED, event.timeStatusAt(WINDOW_END.plusHours(1)));
    }

    @Test
    void 非法时间链与非法参数被拒绝() {
        // 申报截止不晚于建立时刻
        assertThrows(IllegalStateException.class, () -> service.create("EV-B1", "T-1001",
                DrDirection.PEAK_SHAVE, WINDOW_START, WINDOW_END, new BigDecimal("800"), CREATE_AT, CREATE_AT));
        // 申报截止晚于窗口开始（截止必须在响应窗口之前）
        assertThrows(IllegalArgumentException.class, () -> service.create("EV-B2", "T-1001",
                DrDirection.PEAK_SHAVE, WINDOW_START, WINDOW_END, new BigDecimal("800"),
                WINDOW_START.plusMinutes(30), CREATE_AT));
        // 窗口起点等于终点
        assertThrows(IllegalArgumentException.class, () -> service.create("EV-B3", "T-1001",
                DrDirection.PEAK_SHAVE, WINDOW_START, WINDOW_START, new BigDecimal("800"), DEADLINE, CREATE_AT));
        // 目标调节量为零
        assertThrows(IllegalArgumentException.class, () -> service.create("EV-B4", "T-1001",
                DrDirection.PEAK_SHAVE, WINDOW_START, WINDOW_END, BigDecimal.ZERO, DEADLINE, CREATE_AT));
        // 方向为空
        assertThrows(NullPointerException.class, () -> service.create("EV-B5", "T-1001",
                null, WINDOW_START, WINDOW_END, new BigDecimal("800"), DEADLINE, CREATE_AT));
    }

    @Test
    void 重复建立同一事件被拒绝() {
        createDefault("EV-5303");
        assertThrows(IllegalStateException.class, () -> createDefault("EV-5303"));
    }

    @Test
    void 仅组织方租户范围可发布授权修订结束() {
        createDefault("EV-5304");
        // 运营商（场站清单形态）不得发布
        assertThrows(IllegalStateException.class,
                () -> service.publish(operatorScope("T-1001", "S-11101"), "EV-5304", CREATE_AT));
        // 其他租户平台不得发布
        assertThrows(IllegalStateException.class,
                () -> service.publish(platformScope("T-2001"), "EV-5304", CREATE_AT));
        // 空范围不得发布（禁止退化为全量操作）
        assertThrows(IllegalStateException.class,
                () -> service.publish(emptyScope("T-1001"), "EV-5304", CREATE_AT));

        service.publish(platformScope("T-1001"), "EV-5304", CREATE_AT);
        // 其他租户平台不得授权 / 修订 / 结束
        assertThrows(IllegalStateException.class, () -> service.authorizeParticipation(
                platformScope("T-2001"), "EV-5304", List.of("S-11101"), "platform-acc", CREATE_AT));
        assertThrows(IllegalStateException.class, () -> service.reviseTarget(
                platformScope("T-2001"), "EV-5304", new BigDecimal("900"), CREATE_AT));
        assertThrows(IllegalStateException.class,
                () -> service.end(platformScope("T-2001"), "EV-5304", CREATE_AT));
    }

    @Test
    void 参与授权与目标修订递增事件版本并留痕() {
        createDefault("EV-5305");
        service.publish(platformScope("T-1001"), "EV-5305", CREATE_AT);
        assertEquals(1, service.require("EV-5305").getVersion());

        ChargeDrEvent authorized = service.authorizeParticipation(platformScope("T-1001"), "EV-5305",
                List.of("S-11101", "S-11201"), "platform-acc", CREATE_AT.plusMinutes(5));
        assertEquals(2, authorized.getVersion());
        assertEquals(Set.of("S-11101", "S-11201"), authorized.authorizedStationIds());
        assertEquals(2, authorized.participationRecords().size());
        assertTrue(authorized.isStationAuthorized("S-11101"));

        ChargeDrEvent revised = service.reviseTarget(platformScope("T-1001"), "EV-5305",
                new BigDecimal("1000"), CREATE_AT.plusMinutes(10));
        assertEquals(3, revised.getVersion());
        assertEquals(new BigDecimal("1000"), revised.getTargetAdjustKw());
    }

    @Test
    void 申报截止后禁止授权与修订() {
        createDefault("EV-5306");
        service.publish(platformScope("T-1001"), "EV-5306", CREATE_AT);
        // 截止时刻本身即拒绝（截止时刻起关闭）
        assertThrows(IllegalStateException.class, () -> service.authorizeParticipation(
                platformScope("T-1001"), "EV-5306", List.of("S-11101"), "platform-acc", DEADLINE));
        assertThrows(IllegalStateException.class, () -> service.reviseTarget(
                platformScope("T-1001"), "EV-5306", new BigDecimal("900"), DEADLINE.plusSeconds(1)));
    }

    @Test
    void 结束事件后生命周期终态() {
        createDefault("EV-5307");
        service.publish(platformScope("T-1001"), "EV-5307", CREATE_AT);
        ChargeDrEvent ended = service.end(platformScope("T-1001"), "EV-5307", CREATE_AT.plusMinutes(5));
        assertEquals(EventLifecycle.ENDED, ended.getLifecycle());
        assertNotNull(ended.getEndedAt());
        assertThrows(IllegalStateException.class, () -> service.end(platformScope("T-1001"), "EV-5307", CREATE_AT));
    }

    @Test
    void 事件查询按数据范围隔离与短路() {
        createDefault("EV-A1");
        service.publish(platformScope("T-1001"), "EV-A1", CREATE_AT);
        service.authorizeParticipation(platformScope("T-1001"), "EV-A1",
                List.of("S-11101"), "platform-acc", CREATE_AT.plusMinutes(1));

        // T-2001 的事件，T-1001 任何角色都不可见
        service.create("EV-X1", "T-2001", DrDirection.VALLEY_FILL,
                WINDOW_START, WINDOW_END, new BigDecimal("500"), DEADLINE, CREATE_AT);
        service.publish(platformScope("T-2001"), "EV-X1", CREATE_AT);

        // 平台仅见本租户事件
        List<ChargeDrEvent> platformView = service.queryEvents(platformScope("T-1001"));
        assertEquals(List.of("EV-A1"), platformView.stream().map(ChargeDrEvent::getEventId).collect(Collectors.toList()));
        assertTrue(service.queryEvents(platformScope("T-2001")).stream()
                .noneMatch(e -> e.getEventId().equals("EV-A1")), "跨租户事件不可见");

        // 授权场站的运营商可见共享事件
        List<ChargeDrEvent> operatorView = service.queryEvents(operatorScope("T-1001", "S-11101"));
        assertEquals(List.of("EV-A1"), operatorView.stream().map(ChargeDrEvent::getEventId).collect(Collectors.toList()));

        // 未获授权的场站不可见
        assertTrue(service.queryEvents(operatorScope("T-1001", "S-99999")).isEmpty(),
                "未获参与授权的场站不可见共享事件");

        // 已建立未发布的事件对外不可见（平台自己可见）
        createDefault("EV-A2-CREATED");
        assertTrue(service.queryEvents(operatorScope("T-1001", "S-11101")).stream()
                .noneMatch(e -> e.getEventId().equals("EV-A2-CREATED")), "未发布事件对场站侧不可见");
        assertEquals(2, service.queryEvents(platformScope("T-1001")).size(), "平台可见本租户全部事件（含未发布）");

        // 空范围短路，禁止退化为全量
        assertTrue(service.queryEvents(emptyScope("T-1001")).isEmpty());
    }
}

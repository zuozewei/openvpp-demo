package com.openvpp.market.charge;

import com.openvpp.common.context.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 场站申报服务 —— 申报提交/确认/拒绝/撤回与容量预占编排（第 53 篇底座）。
 *
 * 硬规则（对应验收矩阵"申报正确性"）：
 * 1. 截止守门：申报截止时刻起（含时刻本身）一律拒绝，截止前才受理；
 * 2. 容量守门：申报容量不得超过所引用能力快照在事件方向上的可信容量列；
 *    （场站， 事件窗口）并发占用总量守恒 —— 原子检查-占用在台账内完成；
 * 3. 快照守门：只认可未过期且会话指纹未变化的能力快照版本，过期/会话变化不得用旧容量承诺；
 * 4. 参与守门：场站必须获得事件显式参与授权，未授权不可申报；
 * 5. 幂等：提交请求标识去重，重复请求返回原申报单、不重复占用；
 * 6. 释放：撤回/拒绝即释放预占并留痕，REJECTED/WITHDRAWN 为终态；
 * 7. 数据范围：申报查询与提交均接受 DataScope —— 场站须在调用方范围内，
 *    空范围短路返回空结果，禁止退化为全量查询。
 *
 * 角色约束（场站提交、运营商确认）由 controller 层 @RequireRole 执行，
 * 本服务以 DataScope 做租户/场站级防御。
 *
 * 教学实现为单 JVM 内存版：declare 串行化保证请求去重与台账一致性；
 * 分布式部署时去重与预占需换外部唯一约束/原子扣减，口径不变。
 */
public class ChargeDeclarationService {

    private static final Logger log = LoggerFactory.getLogger(ChargeDeclarationService.class);

    private final ChargeDrEventService eventService;
    private final SnapshotRegistry snapshotRegistry;
    private final CapacityOccupancyLedger occupancyLedger;

    private final Map<String, ChargeDeclaration> declarations = new ConcurrentHashMap<>();
    private final Map<String, String> declarationIdByRequestId = new ConcurrentHashMap<>();

    public ChargeDeclarationService(ChargeDrEventService eventService,
                                    SnapshotRegistry snapshotRegistry,
                                    CapacityOccupancyLedger occupancyLedger) {
        this.eventService = Objects.requireNonNull(eventService, "事件服务不能为空");
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "快照登记簿不能为空");
        this.occupancyLedger = Objects.requireNonNull(occupancyLedger, "容量预占台账不能为空");
    }

    /**
     * 提交申报（幂等）。同一 requestId 重复提交直接返回原申报单，不重复校验、不重复占用。
     *
     * @param callerScope    调用方数据范围（场站须在其中）
     * @param eventId        事件标识
     * @param stationId      申报场站
     * @param tenantId       场站归属租户（数据范围锚定键）
     * @param declaredKw     申报容量（kW）
     * @param snapshotVersion 引用的能力快照版本
     * @param requestId      提交请求标识（幂等键）
     * @param at             提交时刻
     * @return 申报单（首次提交新建；幂等命中返回原单）
     */
    public synchronized ChargeDeclaration declare(DataScope callerScope, String eventId, String stationId,
                                                  String tenantId, BigDecimal declaredKw,
                                                  String snapshotVersion, String requestId,
                                                  LocalDateTime at) {
        Objects.requireNonNull(at, "提交时刻 at 不能为空");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("提交请求标识 requestId 不能为空");
        }
        String idempotentHit = declarationIdByRequestId.get(requestId);
        if (idempotentHit != null) {
            ChargeDeclaration existing = declarations.get(idempotentHit);
            log.info("申报幂等命中: requestId {} 返回原申报单 {}", requestId, idempotentHit);
            return existing;
        }

        requireStationWithinScope(callerScope, stationId, tenantId);

        ChargeDrEvent event = eventService.require(eventId);
        if (event.getLifecycle() != EventLifecycle.PUBLISHED) {
            throw new IllegalStateException("事件未发布或已结束，不予受理申报: " + eventId);
        }
        if (!at.isBefore(event.getDeclareDeadline())) {
            throw new IllegalStateException("已过申报截止时刻，申报被拒绝: " + eventId
                    + "（截止 " + event.getDeclareDeadline() + "，提交 " + at + "）");
        }
        if (!event.isStationAuthorized(stationId)) {
            throw new IllegalStateException("场站未获事件参与授权，申报被拒绝: " + stationId);
        }
        if (declaredKw == null || declaredKw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("申报容量必须为正: " + declaredKw);
        }

        CapabilitySnapshot snapshot = snapshotRegistry.assertUsable(stationId, snapshotVersion, at);
        BigDecimal credibleCapacity = snapshot.credibleCapacityFor(event.getDirection());
        if (declaredKw.compareTo(credibleCapacity) > 0) {
            throw new IllegalStateException("申报容量超过可信容量: 申报 " + declaredKw + " kW，"
                    + event.getDirection().getLabel() + "可信容量 " + credibleCapacity + " kW（快照版本 "
                    + snapshotVersion + "）");
        }

        String declarationId = UUID.randomUUID().toString();
        boolean occupied = occupancyLedger.tryOccupy(stationId, event.getWindowStart(), event.getWindowEnd(),
                declarationId, declaredKw, credibleCapacity, at);
        if (!occupied) {
            BigDecimal occupiedKw = occupancyLedger.occupiedKw(stationId, event.getWindowStart(), event.getWindowEnd());
            throw new IllegalStateException("容量预占不足，申报被拒绝: 申报 " + declaredKw + " kW，"
                    + event.getDirection().getLabel() + "可信容量 " + credibleCapacity + " kW，已占用 "
                    + occupiedKw + " kW");
        }

        ChargeDeclaration declaration = new ChargeDeclaration(declarationId, eventId, tenantId, stationId,
                declaredKw, snapshotVersion, requestId, at);
        declarations.put(declarationId, declaration);
        declarationIdByRequestId.put(requestId, declarationId);
        log.info("申报提交: {} 事件 {} 场站 {} 容量 {} kW 快照版本 {} 请求标识 {}",
                declarationId, eventId, stationId, declaredKw, snapshotVersion, requestId);
        return declaration;
    }

    /**
     * 运营商确认申报：SUBMITTED → CONFIRMED。只有 CONFIRMED 的申报才进入后续派单（第 54 篇）。
     */
    public synchronized ChargeDeclaration confirm(DataScope callerScope, String declarationId, LocalDateTime at) {
        ChargeDeclaration declaration = requireVisible(callerScope, declarationId);
        declaration.transitTo(DeclarationStatus.CONFIRMED);
        log.info("申报确认: {} 事件 {} 场站 {} 容量 {} kW", declarationId,
                declaration.getEventId(), declaration.getStationId(), declaration.getDeclaredKw());
        return declaration;
    }

    /** 运营商拒绝申报：SUBMITTED → REJECTED，释放容量预占 */
    public synchronized ChargeDeclaration reject(DataScope callerScope, String declarationId,
                                                 String reason, LocalDateTime at) {
        ChargeDeclaration declaration = requireVisible(callerScope, declarationId);
        declaration.transitTo(DeclarationStatus.REJECTED);
        declaration.markReason(reason);
        releaseOccupancy(declaration, at);
        log.warn("申报拒绝: {} 事件 {} 场站 {} 原因 {}", declarationId,
                declaration.getEventId(), declaration.getStationId(), reason);
        return declaration;
    }

    /** 场站撤回申报：SUBMITTED / CONFIRMED → WITHDRAWN，释放容量预占 */
    public synchronized ChargeDeclaration withdraw(DataScope callerScope, String declarationId, LocalDateTime at) {
        ChargeDeclaration declaration = requireVisible(callerScope, declarationId);
        declaration.transitTo(DeclarationStatus.WITHDRAWN);
        releaseOccupancy(declaration, at);
        log.info("申报撤回: {} 事件 {} 场站 {} 容量 {} kW", declarationId,
                declaration.getEventId(), declaration.getStationId(), declaration.getDeclaredKw());
        return declaration;
    }

    public ChargeDeclaration require(String declarationId) {
        ChargeDeclaration declaration = declarations.get(declarationId);
        if (declaration == null) {
            throw new IllegalArgumentException("未登记申报单: " + declarationId);
        }
        return declaration;
    }

    /**
     * 按数据范围查申报：
     * 1. 空范围短路返回空结果（禁止退化为全量查询）；
     * 2. tenantWide（平台）：仅本租户场站的申报；
     * 3. 其余形态（运营商/场站/服务任务）：仅范围内场站的申报。
     */
    public List<ChargeDeclaration> queryDeclarations(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        if (scope.isTenantWide()) {
            return declarations.values().stream()
                    .filter(d -> scope.getTenantId().equals(d.getTenantId()))
                    .sorted(Comparator.comparing(ChargeDeclaration::getDeclarationId))
                    .collect(Collectors.toList());
        }
        return declarations.values().stream()
                .filter(d -> scope.getStationIds().contains(d.getStationId()))
                .sorted(Comparator.comparing(ChargeDeclaration::getDeclarationId))
                .collect(Collectors.toList());
    }

    /** 场站范围守卫：空范围拒绝、tenantWide 锚定租户、清单形态须含场站 */
    private void requireStationWithinScope(DataScope callerScope, String stationId, String tenantId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止提交申报，禁止退化为全量操作");
        }
        if (callerScope.isTenantWide()) {
            if (!callerScope.getTenantId().equals(tenantId)) {
                throw new IllegalStateException("场站归属租户不在当前数据范围内: " + tenantId);
            }
            return;
        }
        if (!callerScope.getStationIds().contains(stationId)) {
            throw new IllegalStateException("场站不在当前数据范围内: " + stationId);
        }
    }

    /** 申报可见性守卫 + 取用 */
    private ChargeDeclaration requireVisible(DataScope callerScope, String declarationId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        ChargeDeclaration declaration = require(declarationId);
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止操作申报，禁止退化为全量操作");
        }
        if (callerScope.isTenantWide()) {
            if (!callerScope.getTenantId().equals(declaration.getTenantId())) {
                throw new IllegalStateException("申报不在当前数据范围内: " + declarationId);
            }
            return declaration;
        }
        if (!callerScope.getStationIds().contains(declaration.getStationId())) {
            throw new IllegalStateException("申报不在当前数据范围内: " + declarationId);
        }
        return declaration;
    }

    private void releaseOccupancy(ChargeDeclaration declaration, LocalDateTime at) {
        ChargeDrEvent event = eventService.require(declaration.getEventId());
        occupancyLedger.release(declaration.getStationId(), event.getWindowStart(), event.getWindowEnd(),
                declaration.getDeclarationId(), at);
    }
}

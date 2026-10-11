package com.openvpp.settlement.assess;

import com.openvpp.common.context.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基线冻结登记簿 —— 响应前冻结的执行落点（第 56 篇底座）。
 *
 * 三道质量门（对应 3.5.5"基线与评估"格"冻结后拒写且响应前完成"）：
 * <ol>
 *   <li>响应前冻结：frozenAt 不得晚于响应窗口开始 —— 窗口开始后再冻结的参照系
 *       可能已被响应期数据污染，一律拒绝；</li>
 *   <li>响应期数据不入样：每个冻结样本的采样时刻必须早于响应窗口开始
 *       （与第 17 篇基线核算"响应日当天及之后数据不入池"同一防线）；</li>
 *   <li>锁后拒写：同（事件, 场站, 资源）已冻结时 —— 内容完全一致幂等返回原记录；
 *       任何字段改数（样本、版本、时刻、窗口）一律拒绝，杜绝"响应后改基线"。</li>
 * </ol>
 *
 * 查询按 DataScope 收窄：平台看本租户全部冻结，运营商/场站仅看绑定场站，
 * 空范围短路返回空，禁止退化为全量查询。教学实现为单 JVM 内存版（synchronized）。
 */
public class BaselineFreezeRegistry {

    private static final Logger log = LoggerFactory.getLogger(BaselineFreezeRegistry.class);

    private final Map<String, BaselineFreeze> freezesByKey = new LinkedHashMap<>();

    /**
     * 登记一次基线冻结（三道质量门内）。同键同内容幂等返回，改数拒绝。
     */
    public synchronized BaselineFreeze freeze(BaselineFreeze freeze) {
        Objects.requireNonNull(freeze, "冻结记录不能为空");
        if (!freeze.getFrozenAt().isBefore(freeze.getWindowStart())) {
            throw new IllegalStateException("基线冻结必须早于响应窗口开始（响应前冻结）: 冻结时刻 "
                    + freeze.getFrozenAt() + "，窗口开始 " + freeze.getWindowStart());
        }
        long windowStartMs = freeze.windowStartMs();
        for (FrozenBaselinePoint point : freeze.getPoints()) {
            if (point.getSampledAtMs() >= windowStartMs) {
                throw new IllegalStateException("响应期数据不得入样: 样本采样时刻 " + point.getSampledAtMs()
                        + " 不早于响应窗口开始 " + windowStartMs + "（基线版本 "
                        + freeze.getBaselineVersion() + "）");
            }
        }
        String key = keyOf(freeze.getEventId(), freeze.getStationId(), freeze.getResourceId());
        BaselineFreeze existing = freezesByKey.get(key);
        if (existing != null) {
            if (existing.equals(freeze)) {
                log.info("基线冻结幂等命中: {} 返回原冻结版本 {}", key, existing.getBaselineVersion());
                return existing;
            }
            throw new IllegalStateException("基线已冻结，锁后拒写: " + key
                    + "（已冻结版本 " + existing.getBaselineVersion() + "，样本来源 "
                    + existing.getSampleSource() + "）");
        }
        freezesByKey.put(key, freeze);
        log.info("基线冻结完成: {} 方向 {} 冻结时刻 {} 窗口 {} ~ {} 样本来源 {} 规则版本 {} 基线版本 {} 样本 {} 点",
                key, freeze.getDirection().getLabel(), freeze.getFrozenAt(),
                freeze.getWindowStart(), freeze.getWindowEnd(), freeze.getSampleSource(),
                freeze.getRuleVersion(), freeze.getBaselineVersion(), freeze.getPoints().size());
        return freeze;
    }

    /** 冻结版本取用：评估只读入口，未冻结即拒绝（无冻结不评估） */
    public synchronized BaselineFreeze requireFrozen(String eventId, String stationId, String resourceId) {
        BaselineFreeze freeze = freezesByKey.get(keyOf(eventId, stationId, resourceId));
        if (freeze == null) {
            throw new IllegalStateException("基线未冻结，拒绝评估: 事件 " + eventId
                    + " 场站 " + stationId + " 资源 " + resourceId
                    + "（响应前须先完成基线冻结）");
        }
        return freeze;
    }

    public synchronized Optional<BaselineFreeze> find(String eventId, String stationId, String resourceId) {
        return Optional.ofNullable(freezesByKey.get(keyOf(eventId, stationId, resourceId)));
    }

    /**
     * 按数据范围查冻结记录：
     * 空范围短路返回空；tenantWide 仅本租户；其余形态仅范围内场站。
     */
    public synchronized List<BaselineFreeze> query(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        List<BaselineFreeze> matched = new ArrayList<>();
        for (BaselineFreeze freeze : freezesByKey.values()) {
            if (!scope.getTenantId().equals(freeze.getOrganizerTenantId())) {
                continue;
            }
            if (!scope.isTenantWide() && !scope.getStationIds().contains(freeze.getStationId())) {
                continue;
            }
            matched.add(freeze);
        }
        matched.sort(Comparator.comparing(BaselineFreeze::getEventId)
                .thenComparing(BaselineFreeze::getStationId)
                .thenComparing(BaselineFreeze::getResourceId));
        return List.copyOf(matched);
    }

    private static String keyOf(String eventId, String stationId, String resourceId) {
        return eventId + "|" + stationId + "|" + resourceId;
    }
}

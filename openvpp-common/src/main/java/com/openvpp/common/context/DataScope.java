package com.openvpp.common.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据范围解析结果（不可变值对象）—— 查询过滤条件的唯一合法来源：
 * 业务查询必须同时携带 tenantId 条件与场站条件，不得自行放宽为全量查询。
 *
 * 两种形态：
 * 1. tenantWide=true：可见范围为锚定 tenantId 内全部场站（仅平台角色可得）；
 * 2. tenantWide=false：可见范围仅限 stationIds，空集合即"明确无可见数据"，
 *    调用方必须按空范围短路查询，禁止退化为全量查询。
 */
public final class DataScope {

    private final String tenantId;
    private final Set<String> stationIds;
    private final boolean tenantWide;

    private DataScope(String tenantId, Set<String> stationIds, boolean tenantWide) {
        this.tenantId = tenantId;
        this.stationIds = Collections.unmodifiableSet(new LinkedHashSet<>(stationIds));
        this.tenantWide = tenantWide;
    }

    static DataScope tenantWide(String tenantId) {
        return new DataScope(tenantId, Collections.emptySet(), true);
    }

    static DataScope stations(String tenantId, Set<String> stationIds) {
        return new DataScope(tenantId, stationIds, false);
    }

    /** 锚定租户：任何查询必须带此 tenantId 条件 */
    public String getTenantId() {
        return tenantId;
    }

    /** 场站清单（不可变）；tenantWide=true 时为空集合（表示不限场站、仅限本租户） */
    public Set<String> getStationIds() {
        return stationIds;
    }

    /** true=锚定租户内全部场站可见（仅平台角色可得） */
    public boolean isTenantWide() {
        return tenantWide;
    }

    /** 明确无可见数据：调用方必须按空范围短路查询，禁止退化为全量查询 */
    public boolean isEmpty() {
        return !tenantWide && stationIds.isEmpty();
    }
}

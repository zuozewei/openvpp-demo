package com.openvpp.common.context;

/**
 * 平台角色类型 —— 充电桩需求响应运营链路的三方业务主体 + 一种系统身份：
 * 平台方发起事件并分配运营商，运营商承接事件并向场站派单，场站申报响应能力并执行到场指令；
 * 身份上下文（IdentityContext）与数据范围解析（DataScopeResolver）均以本枚举为分流依据。
 */
public enum RoleType {

    /** 平台方管理员（教学化平台 openvpp-platform 侧运营账号）：发起 DR 事件、分配运营商、平台侧多级分账 */
    PLATFORM_ADMIN,

    /** 运营商（聚合运营主体）：承接平台分配的事件，向场站派单并跟踪执行与申报偏差 */
    OPERATOR,

    /** 场站运营方：申报响应能力、维护本场站充电桩资源、执行到场指令并回执 */
    STATION_OPERATOR,

    /**
     * 跨租户服务任务身份：仅供后台作业（如跨租户结算、效果评估归档）持有，禁止与人类登录态混用。
     * 必须经 IdentityContext.systemTask(...) 显式构造；调用方须为每次使用留存审计记录
     * （任务标识、授权依据、目标范围），确保跨租户访问可追溯、可复核。
     */
    SYSTEM_TASK
}

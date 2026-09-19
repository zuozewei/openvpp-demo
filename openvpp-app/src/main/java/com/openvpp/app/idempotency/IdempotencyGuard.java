package com.openvpp.app.idempotency;

import java.util.Optional;

/**
 * 接口幂等守卫：已完成结果的快速重放缓存（教学演示中 Redis 的业务落点）。
 *
 * 定位是「前置加速」而不是正确性来源：数据库唯一约束 + 事务内原子认领
 * （见 ParkResponseOrchestrator）才是幂等的最终保证。Redis 不可用或未启用时
 * 自动退化为 NoopIdempotencyGuard 纯数据库路径，业务行为不变。
 *
 * 生命周期：tryBegin（登记在途）→ complete（缓存最终结果，须在数据库事务
 * 提交后调用，见编排层事务同步）/ release（失败清理）/ clearNamespace（演示重置清空）。
 * 结果缓存命中即可不落库直接重放；在途登记未命中则继续走数据库认领兜底。
 * 实现方必须自行消化底层故障（Redis 不可用按退化语义返回，不向业务抛异常）。
 */
public interface IdempotencyGuard {

    /** 是否启用（未启用时编排层跳过全部缓存交互，本地教学/测试零 Redis 依赖） */
    boolean isEnabled();

    /** 已完成结果的缓存读取；命中返回 JSON，未命中（含底层故障退化）返回 empty */
    Optional<String> cachedResult(String key);

    /** 登记一次在途执行：true=登记成功（本实例负责完成后写缓存），false=已在途/已有缓存/故障退化 */
    boolean tryBegin(String key);

    /** 执行成功且数据库提交后缓存最终结果（覆盖在途标记）；故障时静默降级 */
    void complete(String key, String resultJson);

    /** 执行失败或事务回滚时清理在途标记；故障时静默降级 */
    void release(String key);

    /** 清空指定前缀命名空间的全部键（演示重置用）；故障时静默降级 */
    void clearNamespace(String prefix);
}

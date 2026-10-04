package com.openvpp.iot.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已用随机数登记簿 —— 防重放的唯一性底座（第 ③ 层）。
 *
 * 职责边界（这是本篇最容易讲错的一点）：
 *  - 时间窗口只限制消息**时效**——出窗的旧令牌失效；
 *  - 窗口**之内**的时间戳全部合法，攻击者在窗口内原样重发截获令牌，
 *    签名、时标检查全部通过——时间窗口对窗口内重放不设防；
 *  - 真正的防重放 = 每条认证消息携带一次性 nonce + 服务端登记已用 nonce，
 *    窗口内照样逐条查重。
 *
 * 演示版内存表 + 惰性过期清理（登记时顺手清掉出窗条目，无后台线程），
 * 单实例语义。生产形态：Redis SET NX PX（原子占位 + TTL 随窗口到期自动清除）
 * 或 DB 唯一约束；多实例部署时必须集中存储，否则重放打到另一台节点即穿透。
 *
 * 登记有效期口径（防重放边界，与消息校验同一套边界定义）：
 *  统一口径：**时间差达到窗口即出窗，边界属于拒绝侧（开区间）**——
 *  消息校验（DeviceTokenService）在 |now − ts| ≥ windowMs 时直接拒绝；
 *  nonce 占位必须覆盖该消息"最晚仍可能被接受"的时刻，即 expiry = ts + windowMs，
 *  且**到期时刻本身仍占位（闭区间保留）**——过期清理只删 expiry < now 的条目。
 *  这样在恰好到期的瞬间，时间窗与 nonce 查重**两层同时拒绝**；
 *  越过到期时刻后由时间窗兜底，nonce 才允许出窗让位。
 *  expiry 以消息时间戳为基准（而非首次接收时间）：设备时钟允许超前，
 *  时间戳为 ts 的消息最晚在 ts + windowMs 之前的瞬间仍可能合法到达。
 */
public class NonceRegistry {

    /** 已用 nonce → 过期时刻（毫秒） */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();
    private final long windowMs;

    public NonceRegistry(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * 登记 nonce：首次出现返回 true 并占位；已出现过（重放）返回 false。
     * 原子语义靠 ConcurrentHashMap.putIfAbsent 保证——并发重放下只有一个线程占位成功。
     *
     * @param messageTimestampMs 消息携带的设备时间戳（有效期基准：覆盖最晚可接受时刻）
     * @param nowMs              平台接收时刻（惰性清理基准）
     */
    public boolean registerOnce(String deviceId, String nonce, long messageTimestampMs, long nowMs) {
        evictExpired(nowMs);
        String key = deviceId + "|" + nonce;
        long expiry;
        try {
            expiry = Math.addExact(messageTimestampMs, windowMs);
        } catch (ArithmeticException overflow) {
            // 极端时标下最晚可接受时刻溢出：防御性取上界占位——该时标的消息
            // 在时间窗关卡（subtractExact 饱和处理）已被拒，占位不参与放行决策
            expiry = Long.MAX_VALUE;
        }
        return usedNonces.putIfAbsent(key, expiry) == null;
    }

    /** 惰性清理：只删已**越过**过期时刻的条目（expiry < now）；到期时刻本身仍占位，与消息校验边界一致 */
    private void evictExpired(long nowMs) {
        usedNonces.values().removeIf(expireAt -> expireAt < nowMs);
    }

    /** 当前登记量（测试与容量观测用） */
    public int size() {
        return usedNonces.size();
    }
}

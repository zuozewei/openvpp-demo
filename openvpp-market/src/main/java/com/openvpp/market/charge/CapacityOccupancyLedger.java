package com.openvpp.market.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 容量预占台账 —— 同一资源同一时间窗并发申报的守门与留痕（第 53 篇"并发占用守恒"执行落点）。
 *
 * 记账口径：按（场站， 响应时间窗）立户，申报成功记一笔占用，撤回/拒绝记一笔释放；
 * tryOccupy 内"检查余量 + 落账"为原子操作 —— 并发下同一窗口的占用总量
 * 永远不超过可信容量上限，不存在"都检查通过、都占用成功"的竞态窗口。
 *
 * 教学实现为单 JVM 内存版（synchronized 原子化）；分布式部署时本台账需换
 * 外部存储 + 原子扣减（或乐观锁），占用/释放口径不变。
 */
public class CapacityOccupancyLedger {

    /** 台账动作 */
    public enum Action {
        OCCUPY, RELEASE
    }

    /** 占用/释放流水 —— 每次余额变动留痕，供审计与测试对账 */
    public static final class OccupancyRecord {

        private final String stationId;
        private final LocalDateTime windowStart;
        private final LocalDateTime windowEnd;
        private final String declarationId;
        private final BigDecimal kw;
        private final Action action;
        private final LocalDateTime at;

        OccupancyRecord(String stationId, LocalDateTime windowStart, LocalDateTime windowEnd,
                        String declarationId, BigDecimal kw, Action action, LocalDateTime at) {
            this.stationId = stationId;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.declarationId = declarationId;
            this.kw = kw;
            this.action = action;
            this.at = at;
        }

        public String getStationId() {
            return stationId;
        }

        public LocalDateTime getWindowStart() {
            return windowStart;
        }

        public LocalDateTime getWindowEnd() {
            return windowEnd;
        }

        public String getDeclarationId() {
            return declarationId;
        }

        public BigDecimal getKw() {
            return kw;
        }

        public Action getAction() {
            return action;
        }

        public LocalDateTime getAt() {
            return at;
        }
    }

    private final Map<String, BigDecimal> occupiedByWindow = new HashMap<>();
    private final Map<String, BigDecimal> heldByDeclaration = new HashMap<>();
    private final List<OccupancyRecord> records = new ArrayList<>();

    private static String windowKey(String stationId, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return stationId + "|" + windowStart + "|" + windowEnd;
    }

    /**
     * 原子预占：占用后该（场站， 窗口）总量不得超过 capacityLimit。
     *
     * @return true=预占成功；false=超出上限，本次未发生任何占用
     */
    public synchronized boolean tryOccupy(String stationId, LocalDateTime windowStart, LocalDateTime windowEnd,
                                          String declarationId, BigDecimal kw, BigDecimal capacityLimit,
                                          LocalDateTime at) {
        Objects.requireNonNull(stationId, "stationId 不能为空");
        Objects.requireNonNull(windowStart, "windowStart 不能为空");
        Objects.requireNonNull(windowEnd, "windowEnd 不能为空");
        Objects.requireNonNull(declarationId, "declarationId 不能为空");
        Objects.requireNonNull(kw, "kw 不能为空");
        Objects.requireNonNull(capacityLimit, "capacityLimit 不能为空");
        Objects.requireNonNull(at, "at 不能为空");
        if (kw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("预占容量必须为正: " + kw);
        }
        String key = windowKey(stationId, windowStart, windowEnd);
        BigDecimal occupied = occupiedByWindow.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal afterOccupy = occupied.add(kw);
        if (afterOccupy.compareTo(capacityLimit) > 0) {
            return false;
        }
        occupiedByWindow.put(key, afterOccupy);
        heldByDeclaration.put(declarationId, kw);
        records.add(new OccupancyRecord(stationId, windowStart, windowEnd, declarationId, kw, Action.OCCUPY, at));
        return true;
    }

    /**
     * 释放指定申报单的占用（撤回/拒绝路径）。按占用时登记的容量回吐，
     * 无对应占用记录时静默跳过 —— 释放不得产生负占用。
     */
    public synchronized void release(String stationId, LocalDateTime windowStart, LocalDateTime windowEnd,
                                     String declarationId, LocalDateTime at) {
        String declarationKey = Objects.requireNonNull(declarationId, "declarationId 不能为空");
        BigDecimal kw = heldByDeclaration.remove(declarationKey);
        if (kw == null) {
            return;
        }
        String key = windowKey(stationId, windowStart, windowEnd);
        BigDecimal occupied = occupiedByWindow.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal afterRelease = occupied.subtract(kw);
        if (afterRelease.signum() < 0) {
            throw new IllegalStateException("容量预占出现负余额，台账不一致: " + key);
        }
        if (afterRelease.signum() == 0) {
            occupiedByWindow.remove(key);
        } else {
            occupiedByWindow.put(key, afterRelease);
        }
        records.add(new OccupancyRecord(stationId, windowStart, windowEnd, declarationId, kw, Action.RELEASE, at));
    }

    /** 指定（场站， 窗口）当前占用总量 */
    public synchronized BigDecimal occupiedKw(String stationId, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return occupiedByWindow.getOrDefault(windowKey(stationId, windowStart, windowEnd), BigDecimal.ZERO);
    }

    /** 全部流水（按发生顺序，不可变视图） */
    public synchronized List<OccupancyRecord> records() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /** 指定场站的流水（按发生顺序，不可变视图） */
    public synchronized List<OccupancyRecord> recordsOf(String stationId) {
        List<OccupancyRecord> matched = new ArrayList<>();
        for (OccupancyRecord record : records) {
            if (record.getStationId().equals(stationId)) {
                matched.add(record);
            }
        }
        return Collections.unmodifiableList(matched);
    }
}

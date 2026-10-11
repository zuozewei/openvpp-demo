package com.openvpp.app.controller;

import com.openvpp.app.idempotency.IdempotencyGuard;
import com.openvpp.app.idempotency.RedisIdempotencyGuard;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.market.charge.SnapshotRegistry;
import com.openvpp.resource.org.OrgRegistryService;
import com.openvpp.common.api.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 专题验收健康检查端点（第 59 篇「部署与恢复」验收格底座）：/api/v1/system/health。
 *
 * 与 /system/ping（骨架自检）不同，本端点逐项探测关键组件的真实状态，供容器交付
 * 与故障注入验收直接对拍：
 *   db               —— 真实连接探测（SELECT 1 + 数据库产品/版本元数据）；
 *   redis            —— 仅 Redis 幂等守卫启用时探测（PING）；本地教学形态（Noop 守卫）
 *                      标记 DISABLED，不做无意义的连接尝试；
 *   idempotencyGuard —— 当前装配的守卫实现（NoopIdempotencyGuard / RedisIdempotencyGuard），
 *                      与 application-docker.yml 的 openvpp.idempotency.redis-enabled 对应；
 *   gateway          —— 接入模式（local 本地模拟 / remote 协议接入）。
 *   fixtures         —— 初始化数据装配核对（演示账号/场站/能力快照计数，与
 *                      docs/acceptance/init-data-inventory.md 清单逐项对应，
 *                      「初始化与镜像版本一致」验收项的在线核对入口）。
 *
 * 整体状态口径：
 *   db DOWN                        → DOWN（持久化不可用，服务不可用）；
 *   Redis 守卫启用但 redis DOWN    → DEGRADED——守卫按退化语义放行数据库路径
 *     （见 RedisIdempotencyGuard），应用仍可用、正确性由数据库唯一约束兜底；
 *   其余                           → UP。
 *
 * 本端点公开（与 /system/ping 同级，不挂登录态拦截器），容器 healthcheck 与
 * 验收脚本均可直接调用；仅暴露组件状态与虚构演示数据计数，不泄露连接明细。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthController.class);

    /** 演示场站清单（与 ChargeDemoFixture 种入口径一致，健康端点只做存在性核对） */
    private static final String[] DEMO_STATIONS = {"cs-station-01", "cs-station-02"};

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotencyGuard;
    private final StringRedisTemplate redis;
    private final OrgRegistryService orgRegistryService;
    private final SnapshotRegistry snapshotRegistry;
    private final String gatewayMode;
    private final String profile;

    public SystemHealthController(DataSource dataSource,
                                  JdbcTemplate jdbc,
                                  IdempotencyGuard idempotencyGuard,
                                  StringRedisTemplate redis,
                                  OrgRegistryService orgRegistryService,
                                  SnapshotRegistry snapshotRegistry,
                                  @Value("${openvpp.gateway.mode:local}") String gatewayMode,
                                  @Value("${spring.profiles.active:default}") String profile) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.idempotencyGuard = idempotencyGuard;
        this.redis = redis;
        this.orgRegistryService = orgRegistryService;
        this.snapshotRegistry = snapshotRegistry;
        this.gatewayMode = gatewayMode;
        this.profile = profile;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        Map<String, Object> db = probeDb();
        Map<String, Object> redisStatus = probeRedis();
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("type", idempotencyGuard.getClass().getSimpleName());
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("mode", gatewayMode);

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("db", db);
        components.put("redis", redisStatus);
        components.put("idempotencyGuard", guard);
        components.put("gateway", gateway);

        Map<String, Object> data = new LinkedHashMap<>();
        // 整体状态置首位：响应中第一个 "status" 即整体状态，组件分项在后，便于脚本与人工目检
        data.put("status", overallStatus(db, redisStatus));
        data.put("components", components);
        data.put("fixtures", probeFixtures());
        data.put("profile", profile);
        return ApiResult.ok(data);
    }

    // ---------------- 组件探测 ----------------

    /** 数据源真实探测：连接级 SELECT 1 + 产品/版本元数据（H2/MySQL 自适配） */
    private Map<String, Object> probeDb() {
        Map<String, Object> db = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            String product = conn.getMetaData().getDatabaseProductName();
            String version = conn.getMetaData().getDatabaseProductVersion();
            db.put("status", "UP");
            db.put("detail", product + " " + version);
        } catch (Exception e) {
            log.warn("健康检查：数据源探测失败: {}", e.getMessage());
            db.put("status", "DOWN");
            db.put("detail", e.getMessage());
        }
        return db;
    }

    /**
     * Redis 探测：仅 Redis 幂等守卫启用时进行（本地 Noop 形态无 Redis 依赖，标 DISABLED）。
     * 任何异常按 DOWN 报告并附原因——守卫自身在业务路径上另有退化语义（见 RedisIdempotencyGuard）。
     */
    private Map<String, Object> probeRedis() {
        Map<String, Object> redisStatus = new LinkedHashMap<>();
        if (!(idempotencyGuard instanceof RedisIdempotencyGuard)) {
            redisStatus.put("status", "DISABLED");
            redisStatus.put("detail", "本地教学形态未启用 Redis 前置幂等（Noop 守卫，幂等由数据库原子认领承担）");
            return redisStatus;
        }
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> String.valueOf(connection.ping()));
            redisStatus.put("status", "UP");
            redisStatus.put("detail", "PING " + pong);
        } catch (Exception e) {
            log.warn("健康检查：Redis 探测失败: {}", e.getMessage());
            redisStatus.put("status", "DOWN");
            redisStatus.put("detail", e.getMessage());
        }
        return redisStatus;
    }

    /** 初始化数据装配核对：演示账号/演示场站/能力快照计数（清单见 docs/acceptance/init-data-inventory.md） */
    private Map<String, Object> probeFixtures() {
        Map<String, Object> fixtures = new LinkedHashMap<>();
        Integer accountCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_account", Integer.class);
        fixtures.put("demoAccounts", accountCount == null ? 0 : accountCount);
        int stationCount = 0;
        int snapshotCount = 0;
        for (String stationId : DEMO_STATIONS) {
            try {
                if (orgRegistryService.requireStation(stationId) != null) {
                    stationCount++;
                }
            } catch (RuntimeException notRegistered) {
                log.warn("健康检查：演示场站未登记: {}", stationId);
            }
            try {
                snapshotRegistry.currentOf(stationId);
                snapshotCount++;
            } catch (RuntimeException noSnapshot) {
                log.warn("健康检查：演示能力快照未登记: {}", stationId);
            }
        }
        fixtures.put("demoStations", stationCount);
        fixtures.put("capabilitySnapshots", snapshotCount);
        return fixtures;
    }

    /** 整体状态：db DOWN → DOWN；Redis 守卫启用但不可达 → DEGRADED；其余 UP */
    private String overallStatus(Map<String, Object> db, Map<String, Object> redisStatus) {
        if ("DOWN".equals(db.get("status"))) {
            return "DOWN";
        }
        if ("DOWN".equals(redisStatus.get("status"))) {
            return "DEGRADED";
        }
        return "UP";
    }
}

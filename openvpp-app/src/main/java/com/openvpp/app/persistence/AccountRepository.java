package com.openvpp.app.persistence;

import com.openvpp.app.auth.Account;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.common.context.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 教学登录账号仓库 —— JdbcTemplate + H2 文件库（沿用 app 持久化方式，docker 交付同表切 MySQL）。
 *
 * 预置 3 个虚构演示账号（与 openvpp-admin 脚手架对齐，密码统一 Openvpp@2026、散列存储）：
 *   platform_admin —— 平台运营，租户内全场站（tenantWide）；
 *   operator_admin —— 运营商，绑定 cs-station-01 / cs-station-02；
 *   station_admin  —— 场站运营，绑定 cs-station-01。
 * 种入策略为 insert-if-absent（重复键即跳过），重启/重建上下文均可安全执行。
 */
@Repository
public class AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    /** 演示账号统一密码（仅用于虚构教学账号，散列后落库） */
    public static final String DEMO_PASSWORD = "Openvpp@2026";

    /** 教学演示租户：三方演示账号同属一个租户，跨租户隔离场景属后续篇目 */
    public static final String DEMO_TENANT = "tenant-openvpp";

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 演示账号种入：已存在则跳过（账号无主数据变更入口，种入语义即"确保存在"） */
    @PostConstruct
    void seedDemoAccounts() {
        for (Account account : demoAccounts()) {
            try {
                insert(account);
            } catch (DuplicateKeyException e) {
                log.info("演示账号已存在，跳过预置: {}", account.getLogin());
            }
        }
    }

    /** 3 个虚构演示账号定义（盐固定仅便于教学复算，真实形态应逐账号随机生成） */
    static List<Account> demoAccounts() {
        return List.of(
                new Account("platform_admin",
                        PasswordDigest.hash(DEMO_PASSWORD, "salt-platform-demo"),
                        RoleType.PLATFORM_ADMIN, DEMO_TENANT,
                        orderedSet(), "平台运营-演示账号"),
                new Account("operator_admin",
                        PasswordDigest.hash(DEMO_PASSWORD, "salt-operator-demo"),
                        RoleType.OPERATOR, DEMO_TENANT,
                        orderedSet("cs-station-01", "cs-station-02"), "运营商-演示账号"),
                new Account("station_admin",
                        PasswordDigest.hash(DEMO_PASSWORD, "salt-station-demo"),
                        RoleType.STATION_OPERATOR, DEMO_TENANT,
                        orderedSet("cs-station-01"), "场站-演示账号"));
    }

    /** 多元素场站清单必须使用有序集合：Set.of 的迭代顺序按 JVM 加盐随机，会破坏清单稳定性 */
    private static Set<String> orderedSet(String... stationIds) {
        return new LinkedHashSet<>(Arrays.asList(stationIds));
    }

    /** 新增账号（主键 login，重复键抛 DuplicateKeyException） */
    public void insert(Account account) {
        jdbc.update("INSERT INTO sys_account "
                        + "(login,password_hash,role,tenant_id,station_ids,display_name,created_ms) VALUES(?,?,?,?,?,?,?)",
                account.getLogin(), account.getPasswordHash(), account.getRole().name(),
                account.getTenantId(), String.join(",", account.getStationIds()),
                account.getDisplayName(), System.currentTimeMillis());
    }

    /** 按登录名查账号，不存在返回 Optional.empty() */
    public Optional<Account> findByLogin(String login) {
        List<Account> accounts = jdbc.query(
                "SELECT * FROM sys_account WHERE login=?", (rs, rowNum) -> mapRow(rs), login);
        return accounts.stream().findFirst();
    }

    private Account mapRow(ResultSet rs) throws SQLException {
        String stations = rs.getString("station_ids");
        Set<String> stationIds = new LinkedHashSet<>();
        if (stations != null && !stations.isBlank()) {
            Arrays.stream(stations.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(stationIds::add);
        }
        return new Account(rs.getString("login"), rs.getString("password_hash"),
                RoleType.valueOf(rs.getString("role")), rs.getString("tenant_id"),
                stationIds, rs.getString("display_name"));
    }
}

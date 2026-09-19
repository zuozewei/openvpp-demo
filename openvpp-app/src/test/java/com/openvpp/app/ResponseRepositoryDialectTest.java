package com.openvpp.app;

import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.persistence.ResponseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 方言回归（第 25 篇 docker 交付）：
 * ResponseRepository 的幂等 upsert 在 MySQL 下为 INSERT .. ON DUPLICATE KEY UPDATE
 * （H2 为 MERGE INTO .. KEY），此处用 H2 MySQL 兼容模式 + 显式方言开关跑通全链路，
 * 验证 docker 交付的 SQL 语法与语义，不需要真实 MySQL 容器。
 * 数值断言沿用 PARK-DEMO.md 手工核算底稿。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:openvpp-mysql-dialect-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1",
        "openvpp.db.dialect=mysql"
})
class ResponseRepositoryDialectTest {

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @Autowired
    private ResponseRepository repo;

    @Test
    void mysql方言全链路_闭环金额与多轮更正留档符合底稿() {
        // 完整闭环：认领 INSERT → 指令/基线/账单 upsert（MySQL 方言）→ 终态 UPDATE → 守恒
        DemoRunResult r = orchestrator.run("dialect-mysql", "NORMAL",
                new BigDecimal("600"), new BigDecimal("900"));

        assertTrue(r.isFeasible());
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
        assertEquals("SETTLED", repo.taskState("dialect-mysql"));
        assertEquals(0, repo.settleAmount("dialect-mysql").compareTo(repo.allocationSum("dialect-mysql", "V1")));
        assertEquals(1, repo.countTasks("dialect-mysql"));

        // 争议更正（MySQL 方言：FOR UPDATE 锁行 + 留档 INSERT + 审计账单 INSERT-only）
        DemoRunResult c1 = orchestrator.dispute("dialect-mysql", new BigDecimal("380"), "dialect-req-1");
        assertEquals("V2", c1.getCorrectionVersion());

        // 重复提交同键：返回原版本结果
        DemoRunResult replay = orchestrator.dispute("dialect-mysql", new BigDecimal("380"), "dialect-req-1");
        assertEquals("V2", replay.getCorrectionVersion());
        assertTrue(replay.isIdempotentReplay());
        assertEquals(1, repo.countCorrections("dialect-mysql"));

        // 第二轮更正（不同请求）：版本递增 V3，最新版本解析（LENGTH+字典序）正确
        DemoRunResult c2 = orchestrator.dispute("dialect-mysql", new BigDecimal("430"), "dialect-req-2");
        assertEquals("V3", c2.getCorrectionVersion());
        assertEquals(3, repo.listBills("dialect-mysql").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count());
        assertNotNull(repo.settleAmountOfVersion("dialect-mysql", "V3"));
        assertEquals(0, repo.settleAmount("dialect-mysql")
                .compareTo(repo.settleAmountOfVersion("dialect-mysql", "V3")));
        assertEquals(0, repo.settleAmount("dialect-mysql")
                .compareTo(repo.allocationSum("dialect-mysql", "V3")));
    }
}

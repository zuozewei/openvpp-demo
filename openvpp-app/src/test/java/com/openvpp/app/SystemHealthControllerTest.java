package com.openvpp.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康检查端点集成测试（第 59 篇「部署与恢复」验收格：初始化数据与健康检查）。
 *
 * 覆盖口径（本地教学形态）：
 *   整体 UP；数据源真实探测 UP；Redis 前置幂等未启用（Noop 守卫）→ DISABLED；
 *   网关 local 模式；初始化装配核对——3 个演示账号 / 2 个演示场站 / 2 份能力快照，
 *   与 docs/acceptance/init-data-inventory.md 清单一一对应。
 * Redis 启用形态（docker profile）的 DEGRADED 语义由验收脚本实跑注入留档
 * （tools/acceptance/fault-inject-redis.sh + docs/acceptance/ 记录）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库；
        // 库名带 health 专属字面，避免与其他 ApiTest 同配置共享 Spring 上下文（账号表会累积）
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-health-${random.uuid};AUTO_SERVER=TRUE"
})
class SystemHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReportsComponentsAndFixtureInventory() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.components.db.status").value("UP"))
                .andExpect(jsonPath("$.data.components.redis.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.components.idempotencyGuard.type")
                        .value("NoopIdempotencyGuard"))
                .andExpect(jsonPath("$.data.components.gateway.mode").value("local"))
                .andExpect(jsonPath("$.data.fixtures.demoAccounts").value(3))
                .andExpect(jsonPath("$.data.fixtures.demoStations").value(2))
                .andExpect(jsonPath("$.data.fixtures.capabilitySnapshots").value(2));
    }
}

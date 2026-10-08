package com.openvpp.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.app.persistence.AuditLogRepository;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营接口认证底座集成测试（第 52 篇交付）：登录契约、登录态拦截、越角色拒绝、
 * 伪造租户字段无效、无绑定场站空范围、审计落库与按身份查询。
 *
 * 数值/结构断言口径：登录响应字段名与 openvpp-admin 前端会话约定一致
 * （token/role/roleName/displayName，role 为 platform/operator/station 短码）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsAuthApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String ME_URL = "/api/v1/operations/auth/me";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    // ---------- 登录成功：响应契约与三个演示账号 ----------

    @Test
    void platformAdminLoginReturnsFrontendSessionContract() throws Exception {
        JsonNode body = loginSuccess("platform_admin");
        assertTrue(!body.get("token").asText().isEmpty(), "登录成功必须返回 token");
        assertEquals("platform", body.get("role").asText());
        assertEquals("平台运营", body.get("roleName").asText());
        assertEquals("平台运营-演示账号", body.get("displayName").asText());
    }

    @Test
    void operatorAdminLoginReturnsOperatorRole() throws Exception {
        JsonNode body = loginSuccess("operator_admin");
        assertEquals("operator", body.get("role").asText());
        assertEquals("运营商", body.get("roleName").asText());
        assertEquals("运营商-演示账号", body.get("displayName").asText());
    }

    @Test
    void stationOperatorLoginReturnsStationRole() throws Exception {
        JsonNode body = loginSuccess("station_admin");
        assertEquals("station", body.get("role").asText());
        assertEquals("场站运营", body.get("roleName").asText());
        assertEquals("场站-演示账号", body.get("displayName").asText());
    }

    // ---------- 登录失败 ----------

    @Test
    void wrongPasswordReturns401WithMessage() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"platform_admin\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void unknownAccountReturns401SameMessage() throws Exception {
        // 未知账号与口令错误返回同一文案，不区分两种失败（防账号枚举）
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"nobody\",\"password\":\"Openvpp@2026\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void blankAccountOrPasswordReturns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"  \",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("账号不能为空"));
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"platform_admin\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 登录态拦截 ----------

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("缺少会话凭证")));
    }

    @Test
    void meWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get(ME_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("会话无效或已过期")));
    }

    // ---------- 当前身份与工作台范围 ----------

    @Test
    void meReturnsServerSideIdentityForOperator() throws Exception {
        String token = loginSuccess("operator_admin").get("token").asText();
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("operator_admin"))
                .andExpect(jsonPath("$.role").value("operator"))
                .andExpect(jsonPath("$.tenantId").value("tenant-openvpp"))
                .andExpect(jsonPath("$.stationIds.length()").value(2))
                .andExpect(jsonPath("$.stationIds[0]").value("cs-station-01"))
                .andExpect(jsonPath("$.stationIds[1]").value("cs-station-02"))
                .andExpect(jsonPath("$.allowedOperations").isArray())
                .andExpect(jsonPath("$.allowedOperations",
                        org.hamcrest.Matchers.hasItem("dispatch:manage")));
    }

    @Test
    void meIgnoresForgedTenantIdField() throws Exception {
        // 安全边界：请求参数伪造租户字段，服务端一律取会话租户，伪造值被忽略
        String token = loginSuccess("station_admin").get("token").asText();
        mockMvc.perform(get(ME_URL)
                        .param("tenantId", "tenant-forged")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-openvpp"))
                .andExpect(jsonPath("$.role").value("station"));
    }

    @Test
    void unboundStationAccountSeesEmptyScope() throws Exception {
        // 无绑定场站账号：me 返回空场站集合，数据范围解析为空范围（短路查询口径）
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-demo"),
                RoleType.STATION_OPERATOR, AccountRepository.DEMO_TENANT,
                Set.of(), "未绑定场站-演示账号"));
        String token = loginSuccess("station_unbound").get("token").asText();
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationIds.length()").value(0))
                .andExpect(jsonPath("$.allowedOperations.length()").value(
                        org.hamcrest.Matchers.greaterThan(0)));

        IdentityContext context = IdentityContext.of(
                "station_unbound", AccountRepository.DEMO_TENANT, RoleType.STATION_OPERATOR, Set.of());
        assertTrue(DataScopeResolver.resolve(context).isEmpty(),
                "无绑定场站账号的数据范围必须解析为空范围，调用方按空范围短路查询");
    }

    // ---------- 越角色访问与审计 ----------

    @Test
    void crossRoleAuditQueryDenied() throws Exception {
        // 审计查询仅平台角色可见：场站/运营商 token 访问一律 401（服务端 @RequireRole 拦截）
        String stationToken = loginSuccess("station_admin").get("token").asText();
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(stationToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("当前角色无权执行该操作")));

        String operatorToken = loginSuccess("operator_admin").get("token").asText();
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWritesAuditRecordsForSuccessAndFailure() throws Exception {
        int before = auditLogRepository.countByAction(
                AccountRepository.DEMO_TENANT, "platform_admin", "LOGIN");
        loginSuccess("platform_admin");
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"platform_admin\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
        int after = auditLogRepository.countByAction(
                AccountRepository.DEMO_TENANT, "platform_admin", "LOGIN");
        assertEquals(before + 2, after, "成功与失败登录各留一条审计");

        List<Map<String, Object>> records =
                auditLogRepository.listForAccount(AccountRepository.DEMO_TENANT, "platform_admin");
        assertTrue(records.stream().anyMatch(r -> "SUCCESS".equals(r.get("RESULT"))),
                "登录成功须留 SUCCESS 审计");
        assertTrue(records.stream().anyMatch(r -> "REJECTED".equals(r.get("RESULT"))),
                "登录失败须留 REJECTED 审计");
        Map<String, Object> row = records.get(0);
        assertEquals("LOGIN", row.get("ACTION"));
        assertEquals("ACCOUNT", row.get("TARGET_TYPE"));
        assertEquals("platform_admin", row.get("ACCOUNT_ID"));
        assertEquals("PLATFORM_ADMIN", row.get("ROLE"));
        assertTrue(row.get("CREATED_MS") instanceof Long, "审计必须记录时间");
    }

    @Test
    void unknownAccountLoginRejectionStillAudited() throws Exception {
        // 未知账号的登录失败也须留痕：账号名登记、租户/角色记 '-'（身份不可知，不杜撰归属）
        int before = auditLogRepository.countByAction("-", "ghost_account", "LOGIN");
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"ghost_account\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        int after = auditLogRepository.countByAction("-", "ghost_account", "LOGIN");
        assertEquals(before + 1, after, "未知账号的登录失败也须留痕（账号名登记、租户记 -）");
    }

    @Test
    void auditQueryScopingByIdentity() throws Exception {
        // 平台侧经 HTTP 查询：租户内全量（含运营商登录留痕）
        loginSuccess("operator_admin");
        String platformToken = loginSuccess("platform_admin").get("token").asText();
        MvcResult result = mockMvc.perform(get(AUDIT_LOGS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(rows.size() > 0, "平台角色应能看到本租户审计全量");
        boolean sawOperatorLogin = false;
        for (JsonNode row : rows) {
            assertEquals("tenant-openvpp", row.get("tenantId").asText(), "审计行必须锚定本租户");
            if ("operator_admin".equals(row.get("accountId").asText())
                    && "LOGIN".equals(row.get("action").asText())) {
                sawOperatorLogin = true;
            }
        }
        assertTrue(sawOperatorLogin, "平台角色查询应覆盖租户内其他账号的留痕");

        // 按身份收窄：运营商身份（仓库层口径）只可见本账号记录
        IdentityContext operator = IdentityContext.of(
                "operator_admin", AccountRepository.DEMO_TENANT, RoleType.OPERATOR,
                Set.of("cs-station-01", "cs-station-02"));
        List<Map<String, Object>> scoped = auditLogRepository.listForIdentity(operator);
        assertTrue(scoped.stream().allMatch(r -> "operator_admin".equals(r.get("ACCOUNT_ID"))),
                "非平台身份的审计查询必须收窄到本账号");
    }

    // ---------- 辅助 ----------

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode loginSuccess(String account) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + DEMO_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}

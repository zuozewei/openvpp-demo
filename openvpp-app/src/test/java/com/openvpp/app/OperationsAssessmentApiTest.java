package com.openvpp.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.app.persistence.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 效果评估接口集成测试（第 56 篇交付，3.5.3 契约第 7 行 + "基线与评估"验收格）：
 *
 * 覆盖验收口径：演示编排跑通「事件发布 → 快照 → 申报 → 确认 → 响应前基线冻结 →
 * 派单下发 → 模拟遥测 → 事件结束 → 评估（只读冻结基线）」全链路；冻结五要素齐备；
 * 健康场景出核定结论（规则版本/基线版本/计量区间随评估留痕）；缺失场景完整度不足
 * 转人工复核且原因逐条呈现；削峰/填谷两方向计量；看板估算与结算核定量两列分列；
 * 运营商申请幂等；平台复核留审计且幂等；数据范围（平台/运营商/场站/未绑定账号）
 * 与越角色 401、越权 404。
 *
 * 本类全部用 POST /assessments/demo-runs 自造事件（事件标识即运行标识，互不干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsAssessmentApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String ASSESSMENTS_URL = "/api/v1/operations/assessments";
    private static final String DEMO_RUNS_URL = "/api/v1/operations/assessments/demo-runs";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;
    private static final String STATION_ONE = "cs-station-01";
    private static final String PILE_ONE = "pile-cs01-001";
    private static final String RULE_VERSION = "TEACH-ASSESS-2026.1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    // ---------- 健康链路：冻结 → 派单 → 遥测 → 评估（只读冻结基线） ----------

    @Test
    void healthyDemoRunsFullChainAndAssessmentConfirmed() throws Exception {
        JsonNode run = runDemo(platformToken(), "HEALTHY", null);
        assertEquals("PEAK_SHAVE", run.get("direction").asText());
        // 每桩 6 指令前 + 10 响应期 = 16，两桩 32（指令前样点在响应窗外，不作计量证据）
        assertEquals(32, run.get("samplesIngested").asInt());
        String eventId = run.get("eventId").asText();

        // 冻结五要素齐备（每桩一行）
        JsonNode freeze = run.get("freezes").get(0);
        assertEquals(eventId, freeze.get("eventId").asText());
        assertEquals(STATION_ONE, freeze.get("stationId").asText());
        assertTrue(freeze.hasNonNull("frozenAt"), "冻结时刻齐备");
        assertEquals("CONFIRMED_DECLARATION_BASELINE", freeze.get("sampleSource").asText(), "样本来源齐备");
        assertEquals(RULE_VERSION, freeze.get("ruleVersion").asText(), "规则版本齐备");
        assertEquals(eventId + "-BL-v1", freeze.get("baselineVersion").asText(), "基线版本齐备");
        assertTrue(freeze.hasNonNull("windowStart") && freeze.hasNonNull("windowEnd"));

        // 评估：核定结论 + 版本三要素 + 计量区间 + 两列数分列
        JsonNode assessment = findByResource(run.get("assessments"), PILE_ONE);
        assertEquals("CONFIRMED", assessment.get("conclusion").asText());
        assertEquals(RULE_VERSION, assessment.get("ruleVersion").asText(), "评估携带规则版本");
        assertEquals(eventId + "-BL-v1", assessment.get("baselineVersion").asText(), "评估携带基线版本");
        assertEquals("CONFIRMED_DECLARATION_BASELINE", assessment.get("sampleSource").asText());
        assertTrue(assessment.hasNonNull("frozenAt"), "评估携带冻结时刻");
        assertEquals(10, assessment.get("eligibleSamples").asInt(), "响应期 10 个有效样点");
        assertEquals(0, assessment.get("missingSamples").asInt());
        assertEquals(1.0, assessment.get("completenessRatio").asDouble(), 1e-9, "完整度 100%");
        assertTrue(assessment.get("meteringEndMs").asLong() > assessment.get("meteringStartMs").asLong(),
                "计量区间随评估留痕");
        double confirmed = assessment.get("confirmedResponseKwh").asDouble();
        assertTrue(confirmed > 0, "削峰核定响应量为正: " + confirmed);
        assertNotEquals(confirmed, assessment.get("dashboardEstimateKwh").asDouble(),
                "看板估算与结算核定量分列，不得互相替代");
        assertTrue(assessment.get("reviewReasons").isEmpty(), "核定通过无需复核原因");

        // 演示编排留审计（按运行标识收窄：同上下文多用例各触发一次演示编排）
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ASSESSMENT_DEMO_RUN' && @.result=='SUCCESS' "
                        + "&& @.targetId=='" + run.get("runId").asText() + "')]", hasSize(1)));
    }

    // ---------- 缺失场景：完整度不足转人工复核 ----------

    @Test
    void missingPointsScenarioGoesToManualReviewWithReasons() throws Exception {
        JsonNode run = runDemo(platformToken(), "MISSING_POINTS", null);
        JsonNode assessment = findByResource(run.get("assessments"), PILE_ONE);
        assertEquals("MANUAL_REVIEW", assessment.get("conclusion").asText(), "证据不足转人工复核");
        assertEquals(10, assessment.get("eligibleSamples").asInt());
        assertEquals(2, assessment.get("missingSamples").asInt(), "序号 5、6 缺失登记为 2 个缺口");
        assertEquals(10.0 / 12.0, assessment.get("completenessRatio").asDouble(), 1e-9,
                "完整度 = 有效/(有效+缺失) = 10/12");
        assertTrue(assessment.get("reviewReasons").size() >= 1, "待复核原因逐条呈现");
        assertTrue(assessment.toString().contains("完整度不足"), "复核原因写明完整度不足");

        // 待复核记录在查询接口按事件可见
        String eventId = run.get("eventId").asText();
        mockMvc.perform(get(ASSESSMENTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].conclusion", org.hamcrest.Matchers.hasItem("MANUAL_REVIEW")));
    }

    // ---------- 方向性计量：削峰/填谷两方向 ----------

    @Test
    void valleyDirectionMeteredSeparately() throws Exception {
        JsonNode run = runDemo(platformToken(), "HEALTHY", "VALLEY_FILL");
        assertEquals("VALLEY_FILL", run.get("direction").asText());
        JsonNode assessment = findByResource(run.get("assessments"), PILE_ONE);
        assertEquals("VALLEY_FILL", assessment.get("direction").asText());
        assertEquals("CONFIRMED", assessment.get("conclusion").asText());
        assertTrue(assessment.get("confirmedResponseKwh").asDouble() > 0,
                "填谷核定响应量为正（实际 − 基线方向积分）");
    }

    // ---------- 运营商申请评估：幂等返回原记录 ----------

    @Test
    void operatorAppliesAssessmentIdempotently() throws Exception {
        String operatorToken = operatorToken();
        JsonNode run = runDemo(platformToken(), "HEALTHY", null);
        String eventId = run.get("eventId").asText();
        Set<String> demoIds = assessmentIds(run.get("assessments"));

        MvcResult applied = mockMvc.perform(post(ASSESSMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn();
        Set<String> appliedIds = assessmentIds(readTree(applied));
        assertEquals(demoIds, appliedIds, "运营商申请评估幂等返回演示编排的原记录");

        // 申请评估留审计（按事件标识收窄）
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ASSESSMENT_APPLY' && @.result=='SUCCESS' "
                        + "&& @.targetId=='" + eventId + "')]", hasSize(1)));
    }

    @Test
    void applyRejectsUnknownOrInvisibleEvent() throws Exception {
        mockMvc.perform(post(ASSESSMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"EVT-not-exists\"}"))
                .andExpect(status().isNotFound());
        // 缺事件标识：400
        mockMvc.perform(post(ASSESSMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 平台复核：留审计、幂等 ----------

    @Test
    void platformReviewLeavesAuditAndIsIdempotent() throws Exception {
        String platformToken = platformToken();
        JsonNode run = runDemo(platformToken, "MISSING_POINTS", null);
        String assessmentId = run.get("assessments").get(0).get("assessmentId").asText();

        MvcResult reviewed = mockMvc.perform(post(ASSESSMENTS_URL + "/" + assessmentId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"缺测时段重合通信故障，同意按核定值结算\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedBy").value("platform_admin"))
                .andExpect(jsonPath("$.reviewNote").value("缺测时段重合通信故障，同意按核定值结算"))
                .andReturn();
        String reviewedAt = readTree(reviewed).get("reviewedAt").asText();

        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ASSESSMENT_REVIEW' && @.result=='SUCCESS' "
                        + "&& @.targetId=='" + assessmentId + "')]", hasSize(1)));

        // 重复复核幂等：首条留痕为准，无二次审计
        mockMvc.perform(post(ASSESSMENTS_URL + "/" + assessmentId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"换个意见\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedAt").value(reviewedAt));
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ASSESSMENT_REVIEW' && @.targetId=='" + assessmentId + "')]",
                        hasSize(1)));
    }

    @Test
    void reviewOutOfScopeOrUnknownReturns404() throws Exception {
        String platformToken = platformToken();
        mockMvc.perform(post(ASSESSMENTS_URL + "/ASM-not-exists/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 数据范围与角色 ----------

    @Test
    void stationAndOperatorRolesQueryWithinBoundStations() throws Exception {
        JsonNode run = runDemo(platformToken(), "HEALTHY", null);
        String eventId = run.get("eventId").asText();

        mockMvc.perform(get(ASSESSMENTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].stationId",
                        org.hamcrest.Matchers.hasItem(STATION_ONE)));
        mockMvc.perform(get(ASSESSMENTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void unboundStationAccountSeesNothingAndRoleViolationRejected() throws Exception {
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound_asm", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-asm"),
                com.openvpp.common.context.RoleType.STATION_OPERATOR, AccountRepository.DEMO_TENANT,
                Set.of(), "未绑定场站-评估演示账号"));
        String unboundToken = loginSuccess("station_unbound_asm").get("token").asText();
        mockMvc.perform(get(ASSESSMENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 越角色：申请评估仅运营商、复核仅平台、演示编排仅平台；无凭证一律 401
        mockMvc.perform(post(ASSESSMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"EVT-x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(ASSESSMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"EVT-x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(ASSESSMENTS_URL + "/ASM-x/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ASSESSMENTS_URL)).andExpect(status().isUnauthorized());
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
        return readTree(result);
    }

    private String platformToken() throws Exception {
        return loginSuccess("platform_admin").get("token").asText();
    }

    private String operatorToken() throws Exception {
        return loginSuccess("operator_admin").get("token").asText();
    }

    private String stationToken() throws Exception {
        return loginSuccess("station_admin").get("token").asText();
    }

    /** 触发一次评估演示编排并返回响应体 */
    private JsonNode runDemo(String token, String scenario, String direction) throws Exception {
        String body = direction == null
                ? "{\"scenario\":\"" + scenario + "\"}"
                : "{\"scenario\":\"" + scenario + "\",\"direction\":\"" + direction + "\"}";
        MvcResult result = mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return readTree(result);
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode findByResource(JsonNode assessments, String resourceId) {
        for (JsonNode assessment : assessments) {
            if (resourceId.equals(assessment.get("resourceId").asText())) {
                return assessment;
            }
        }
        throw new AssertionError("未找到资源 " + resourceId + " 的评估记录: " + assessments);
    }

    private static Set<String> assessmentIds(JsonNode assessments) {
        Set<String> ids = new HashSet<>();
        for (JsonNode assessment : assessments) {
            ids.add(assessment.get("assessmentId").asText());
        }
        return ids;
    }
}

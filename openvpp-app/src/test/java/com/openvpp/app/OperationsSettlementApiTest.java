package com.openvpp.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.app.persistence.AccountRepository;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多级分账结算接口集成测试（第 57 篇交付，3.5.3 契约第 8 行 + "结算与更正"验收格）：
 *
 * 覆盖验收口径：演示编排产出的 CONFIRMED 评估 → 平台生成结算单（四量 + 评估版本
 * 三要素 + 规则快照 + 账单版本）；分账守恒（合计 = 净实收）与主体归属标识；比例
 * 不合法 400；重复生成幂等；确认幂等（首条留痕为准，审计不重复）；更正版本链与
 * 差额可复算、更正幂等；付款登记仅模拟（渠道 SIMULATED）且重试幂等、不同请求
 * 重复登记 409；已付款禁止更正 409；MANUAL_REVIEW 评估拒绝入结算 409；跨租户
 * 确认/更正 404；越角色 401；数据范围（运营商/场站仅见所属场站单据，未绑定账号
 * 空范围短路）。
 *
 * 本类全部用 POST /assessments/demo-runs 自造事件（事件标识即运行标识，互不干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsSettlementApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String SETTLEMENTS_URL = "/api/v1/operations/settlements";
    private static final String DEMO_RUNS_URL = "/api/v1/operations/assessments/demo-runs";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;
    private static final String STATION_ONE = "cs-station-01";
    private static final String PILE_ONE = "pile-cs01-001";
    private static final String DEMO_TENANT = AccountRepository.DEMO_TENANT;
    private static final String DEMO_ENTITY = "entity-openvpp-demo";
    private static final BigDecimal PRICE = new BigDecimal("2.50");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    // ---------- 全链路：生成 → 确认 → 更正 → 付款登记 ----------

    @Test
    void fullChainGenerateConfirmCorrectAndPayment() throws Exception {
        String platformToken = platformToken();
        JsonNode assessment = healthyAssessment(platformToken);
        String assessmentId = assessment.get("assessmentId").asText();
        double confirmedKwh = assessment.get("confirmedResponseKwh").asDouble();
        assertTrue(confirmedKwh > 0, "健康场景核定电量为正: " + confirmedKwh);

        // 生成：四量 + 评估版本三要素 + 规则快照 + 账单版本
        MvcResult created = mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-full-1",
                                "0.10", "0.20", "0.70")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billVersion").value(1))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.assessmentId").value(assessmentId))
                .andExpect(jsonPath("$.assessmentRuleVersion").value("TEACH-ASSESS-2026.1"))
                .andExpect(jsonPath("$.baselineVersion").value(assessment.get("baselineVersion").asText()))
                .andExpect(jsonPath("$.ruleSnapshot.ruleVersion").value("TEACH-SHARE-2026.1"))
                .andExpect(jsonPath("$.ruleSnapshot.stationRate").value(0.70))
                .andExpect(jsonPath("$.allocations", hasSize(3)))
                .andReturn();
        JsonNode order = readTree(created);
        String settlementId = order.get("settlementId").asText();
        BigDecimal expectedGross = BigDecimal.valueOf(confirmedKwh).multiply(PRICE)
                .setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedGross.doubleValue(), order.get("grossYuan").asDouble(), 1e-9,
                "毛额 = 核定电量 × 补偿单价");
        assertEquals(expectedGross.doubleValue(), order.get("netYuan").asDouble(), 1e-9,
                "缺省考核扣款为 0，净实收 = 毛额");
        assertAllocationConservation(order);

        // 分账主体按归属链解析
        JsonNode platform = findByParty(order.get("allocations"), "PLATFORM");
        JsonNode operator = findByParty(order.get("allocations"), "OPERATOR");
        JsonNode station = findByParty(order.get("allocations"), "STATION");
        assertEquals(DEMO_TENANT, platform.get("partyId").asText(), "平台方 = 组织方租户");
        assertEquals(DEMO_ENTITY, operator.get("partyId").asText(), "运营商 = 场站归属运营主体");
        assertEquals(STATION_ONE, station.get("partyId").asText(), "场站 = 评估场站");

        // 生成留审计
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='SETTLEMENT_GENERATE' && @.result=='SUCCESS' "
                        + "&& @.targetId=='" + settlementId + "')]", hasSize(1)));

        // 确认：幂等（首条留痕为准）
        MvcResult confirmed = mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedBy").value("platform_admin"))
                .andReturn();
        String confirmedAt = readTree(confirmed).get("confirmedAt").asText();
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedAt").value(confirmedAt));
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='SETTLEMENT_CONFIRM' && @.targetId=='"
                        + settlementId + "')]", hasSize(1)));

        // 更正：核定电量加倍 → v2，差额可复算
        MvcResult corrected = mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/corrections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionRequestId\":\"COR-full-1\",\"confirmedResponseKwh\":"
                                + confirmedKwh * 2 + ",\"reason\":\"迟到样点补计量，核定电量上调\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billVersion").value(2))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();
        JsonNode v2 = readTree(corrected);
        BigDecimal expectedV2Net = BigDecimal.valueOf(confirmedKwh * 2).multiply(PRICE)
                .setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedV2Net.doubleValue(), v2.get("netYuan").asDouble(), 1e-9);
        assertAllocationConservation(v2);

        // 更正幂等：同请求标识返回已生成版本
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/corrections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionRequestId\":\"COR-full-1\",\"confirmedResponseKwh\":"
                                + confirmedKwh * 2 + ",\"reason\":\"迟到样点补计量，核定电量上调\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billVersion").value(2));

        // 详情：历史版本 + 更正链齐备，差额由前后版本净实收复算
        MvcResult detail = mockMvc.perform(get(SETTLEMENTS_URL + "/" + settlementId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billVersion").value(2))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.corrections", hasSize(1)))
                .andExpect(jsonPath("$.payments", hasSize(0)))
                .andReturn();
        JsonNode detailBody = readTree(detail);
        JsonNode history = detailBody.get("history");
        JsonNode correction = detailBody.get("corrections").get(0);
        assertEquals(1, correction.get("fromVersion").asInt());
        assertEquals(2, correction.get("toVersion").asInt());
        assertEquals("COR-full-1", correction.get("correctionRequestId").asText());
        assertEquals("platform_admin", correction.get("operator").asText());
        assertTrue(correction.hasNonNull("correctedAt"), "更正时刻齐备");
        assertEquals("迟到样点补计量，核定电量上调", correction.get("reason").asText());
        double diff = history.get(1).get("netYuan").asDouble() - history.get(0).get("netYuan").asDouble();
        assertEquals(diff, correction.get("diffYuan").asDouble(), 1e-9, "更正差额可复算");
        assertEquals(1, history.get(0).get("billVersion").asInt(), "历史版本自 v1 起完整留存");
        assertEquals("CONFIRMED", history.get(0).get("status").asText(), "v1 确认状态留存不被覆盖");

        // 新版本确认后付款登记（模拟）
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billVersion").value(2))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        MvcResult paid = mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRequestId\":\"PAY-full-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("SIMULATED"))
                .andExpect(jsonPath("$.billVersion").value(2))
                .andReturn();
        assertEquals(expectedV2Net.doubleValue(), readTree(paid).get("amountYuan").asDouble(), 1e-9,
                "登记金额 = 付款时单据净实收");

        // 付款重试幂等 + 不同请求重复登记 409
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRequestId\":\"PAY-full-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountYuan").value(expectedV2Net.doubleValue()));
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRequestId\":\"PAY-full-2\"}"))
                .andExpect(status().isConflict());

        // 已登记模拟付款的单据禁止更正
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/corrections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionRequestId\":\"COR-late\",\"confirmedResponseKwh\":"
                                + confirmedKwh * 3 + ",\"reason\":\"付款后尝试更正\"}"))
                .andExpect(status().isConflict());

        // 详情收尾：付款记录随单可查
        mockMvc.perform(get(SETTLEMENTS_URL + "/" + settlementId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].channel").value("SIMULATED"));
    }

    // ---------- 比例不合法 / 幂等生成 ----------

    @Test
    void invalidRatioRejectedAndIdempotentGenerate() throws Exception {
        String platformToken = platformToken();
        JsonNode assessment = healthyAssessment(platformToken);
        String assessmentId = assessment.get("assessmentId").asText();

        // 比例总和不为 1：400
        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-bad-ratio",
                                "0.50", "0.30", "0.30")))
                .andExpect(status().isBadRequest());

        // 负比例：400
        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-neg-ratio",
                                "-0.10", "0.60", "0.50")))
                .andExpect(status().isBadRequest());

        // 扣款超毛额：409
        BigDecimal overDeduction = BigDecimal.valueOf(
                assessment.get("confirmedResponseKwh").asDouble() * 100);
        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, overDeduction, "REQ-over-deduction",
                                "0.10", "0.20", "0.70")))
                .andExpect(status().isConflict());

        // 重复生成幂等：同请求标识返回原单
        MvcResult first = mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-idem",
                                "0.10", "0.20", "0.70")))
                .andExpect(status().isOk())
                .andReturn();
        String settlementId = readTree(first).get("settlementId").asText();
        MvcResult retry = mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-idem",
                                "0.10", "0.20", "0.70")))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(settlementId, readTree(retry).get("settlementId").asText(), "重试返回原单");

        // 该事件下仅此一单
        mockMvc.perform(get(SETTLEMENTS_URL)
                        .param("eventId", assessment.get("eventId").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- MANUAL_REVIEW 拒绝入结算 ----------

    @Test
    void manualReviewAssessmentRejectedFromSettlement() throws Exception {
        String platformToken = platformToken();
        JsonNode run = runDemo(platformToken, "MISSING_POINTS", null);
        JsonNode assessment = findByResource(run.get("assessments"), PILE_ONE);
        assertEquals("MANUAL_REVIEW", assessment.get("conclusion").asText());

        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessment.get("assessmentId").asText(), PRICE, null,
                                "REQ-manual", "0.10", "0.20", "0.70")))
                .andExpect(status().isConflict());
    }

    // ---------- 跨租户与越角色 ----------

    @Test
    void crossTenantAndCrossRoleAccessRejected() throws Exception {
        String platformToken = platformToken();
        JsonNode assessment = healthyAssessment(platformToken);
        MvcResult created = mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessment.get("assessmentId").asText(), PRICE, null,
                                "REQ-guard", "0.10", "0.20", "0.70")))
                .andExpect(status().isOk())
                .andReturn();
        String settlementId = readTree(created).get("settlementId").asText();

        // 越角色：场站/运营商办理一律 401
        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessment.get("assessmentId").asText(), PRICE, null,
                                "REQ-station", "0.10", "0.20", "0.70")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/corrections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionRequestId\":\"COR-x\",\"confirmedResponseKwh\":2.0,"
                                + "\"reason\":\"越角色更正\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SETTLEMENTS_URL)).andExpect(status().isUnauthorized());

        // 跨租户：第二租户平台账号对本租户结算单确认/更正一律 404
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "platform_admin_t2", PasswordDigest.hash(DEMO_PASSWORD, "salt-t2"),
                RoleType.PLATFORM_ADMIN, "tenant-other", Set.of(), "第二租户平台账号"));
        String tenant2Token = loginSuccess("platform_admin_t2").get("token").asText();
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(SETTLEMENTS_URL + "/" + settlementId + "/corrections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionRequestId\":\"COR-cross\",\"confirmedResponseKwh\":2.0,"
                                + "\"reason\":\"跨租户更正\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SETTLEMENTS_URL + "/" + settlementId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        // 跨租户重放本租户生成请求标识：守门先于幂等命中，一律拒绝
        mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessment.get("assessmentId").asText(), PRICE, null,
                                "REQ-guard", "0.10", "0.20", "0.70")))
                .andExpect(status().isConflict());
    }

    // ---------- 数据范围查询 ----------

    @Test
    void scopedRolesQueryOnlyOwnStationSettlements() throws Exception {
        String platformToken = platformToken();
        JsonNode assessment = healthyAssessment(platformToken);
        String assessmentId = assessment.get("assessmentId").asText();
        MvcResult created = mockMvc.perform(post(SETTLEMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(assessmentId, PRICE, null, "REQ-scope",
                                "0.10", "0.20", "0.70")))
                .andExpect(status().isOk())
                .andReturn();
        String settlementId = readTree(created).get("settlementId").asText();
        String eventId = assessment.get("eventId").asText();

        // 运营商/场站仅见所属场站单据（行级可见，与评估查询同口径）
        mockMvc.perform(get(SETTLEMENTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].stationId").value(STATION_ONE));
        mockMvc.perform(get(SETTLEMENTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get(SETTLEMENTS_URL + "/" + settlementId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations", hasSize(3)));

        // 未绑定场站账号：空范围短路
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound_stl", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-stl"),
                RoleType.STATION_OPERATOR, DEMO_TENANT, Set.of(), "未绑定场站-结算演示账号"));
        String unboundToken = loginSuccess("station_unbound_stl").get("token").asText();
        mockMvc.perform(get(SETTLEMENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(SETTLEMENTS_URL + "/" + settlementId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isNotFound());
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

    /** 触发一次健康评估演示编排，取桩一（pile-cs01-001）的 CONFIRMED 评估 */
    private JsonNode healthyAssessment(String token) throws Exception {
        JsonNode run = runDemo(token, "HEALTHY", null);
        JsonNode assessment = findByResource(run.get("assessments"), PILE_ONE);
        assertEquals("CONFIRMED", assessment.get("conclusion").asText());
        return assessment;
    }

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

    private static String generateBody(String assessmentId, BigDecimal price, BigDecimal deduction,
                                       String requestId, String platformRate, String operatorRate,
                                       String stationRate) {
        StringBuilder body = new StringBuilder();
        body.append("{\"assessmentId\":\"").append(assessmentId).append("\"");
        body.append(",\"priceYuanPerKwh\":").append(price.toPlainString());
        if (deduction != null) {
            body.append(",\"deductionYuan\":").append(deduction.toPlainString());
        }
        body.append(",\"requestId\":\"").append(requestId).append("\"");
        body.append(",\"rule\":{\"platformRate\":").append(platformRate);
        body.append(",\"operatorRate\":").append(operatorRate);
        body.append(",\"stationRate\":").append(stationRate).append("}}");
        return body.toString();
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

    private static JsonNode findByParty(JsonNode allocations, String party) {
        for (JsonNode allocation : allocations) {
            if (party.equals(allocation.get("party").asText())) {
                return allocation;
            }
        }
        throw new AssertionError("未找到主体 " + party + " 的分账明细: " + allocations);
    }

    /** 分账守恒：三方金额合计恒等于净实收，且各方金额非负 */
    private static void assertAllocationConservation(JsonNode order) {
        double sum = 0;
        for (JsonNode allocation : order.get("allocations")) {
            double amount = allocation.get("amountYuan").asDouble();
            assertTrue(amount >= 0, "分账金额非负: " + allocation);
            sum += amount;
        }
        assertEquals(order.get("netYuan").asDouble(), sum, 1e-9, "分账合计恒等于净实收");
    }
}

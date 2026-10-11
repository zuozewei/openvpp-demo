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
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 执行监测接口集成测试（第 55 篇交付，3.5.3 契约第 6 行 + "监测与质量"验收格）：
 *
 * 覆盖验收口径：演示编排跑通「计划确认 → 指令下发 → 回执 → 遥测 → 达标」全链路、
 * 曲线视图显式呈现模拟来源/质量标记/指令前样点、零目标绝对偏差、削峰/填谷两方向
 * 告警、迟到/缺口/重复/乱序注入在视图可见且不误判、失联进入核查状态、告警确认
 * 留审计且幂等、数据范围（平台/运营商/场站/未绑定账号）与越角色 401。
 *
 * 启动即由 MonitoringDemoFixture 预跑一次健康场景：本类的查询断言不依赖该预置，
 * 全部用 POST /monitoring/demo-runs 自造事件（事件标识即运行标识，互不干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsMonitoringApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String EXECUTIONS_URL = "/api/v1/operations/monitoring/executions";
    private static final String ALERTS_URL = "/api/v1/operations/monitoring/alerts";
    private static final String DEMO_RUNS_URL = "/api/v1/operations/monitoring/demo-runs";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;
    private static final String DEMO_TENANT = AccountRepository.DEMO_TENANT;
    private static final String STATION_ONE = "cs-station-01";
    private static final String PILE_ONE = "pile-cs01-001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    // 本类用例不需要时间旅行：使用 operationsClock 真实时钟 Bean
    //（@MockBean Clock 的默认 mock getZone() 返回 null，会让启动期 MonitoringDemoFixture 装配失败）

    // ---------- 健康链路：指令 → 回执 → 遥测 → 达标 ----------

    @Test
    void healthyDemoRunsFullChainAndCurveShowsSourceQualityAndBeforeCommand() throws Exception {
        String platformToken = platformToken();
        JsonNode run = runDemo(platformToken, "HEALTHY", null);

        assertEquals("PEAK_SHAVE", run.get("direction").asText());
        assertEquals("HEALTHY", run.get("scenario").asText());
        assertEquals(26, run.get("samplesIngested").asInt(), "每桩 3 指令前 + 10 响应期 = 13，两桩 26");
        assertEquals(0, run.get("gapsDetected").asInt());
        String eventId = run.get("eventId").asText();

        JsonNode evaluation = run.get("evaluations").get(0);
        assertEquals(PILE_ONE, evaluation.get("resourceId").asText());
        assertEquals(STATION_ONE, evaluation.get("stationId").asText());
        assertEquals(eventId + "-C1", evaluation.get("commandNo").asText());
        assertEquals(45.0, evaluation.get("targetPowerKw").asDouble());
        assertEquals("ACKED", evaluation.get("commandState").asText());
        // 回执 ≠ 到位 ≠ 连续达标：三列齐备且健康场景全部成立
        assertTrue(evaluation.get("receiptReceived").asBoolean());
        assertTrue(evaluation.get("attained").asBoolean());
        assertTrue(evaluation.get("steadyAttained").asBoolean());
        assertEquals("MONITORING", evaluation.get("checkStatus").asText());
        assertFalse(evaluation.get("deviationExceeded").asBoolean());
        assertEquals(0, evaluation.get("alerts").size());

        // 曲线：13 点（3 指令前 + 10 响应期），模拟来源显式标记
        JsonNode curve = evaluation.get("curve");
        assertEquals(13, curve.size());
        for (JsonNode point : curve) {
            assertEquals("SIMULATED", point.get("source").asText(), "模拟来源必须显式标记");
            assertEquals(0, point.get("qualityMarks").size(), "健康场景无质量标记");
        }
        // 指令前基线样点：可见但标记不作达标证据
        assertTrue(curve.get(0).get("beforeCommandSent").asBoolean());
        assertFalse(curve.get(0).get("evidenceEligible").asBoolean());
        // 响应期样点：发送后采样，可作证据
        assertFalse(curve.get(3).get("beforeCommandSent").asBoolean());
        assertTrue(curve.get(3).get("evidenceEligible").asBoolean());

        // 查询接口按事件过滤，与健康运行对拍一致
        mockMvc.perform(get(EXECUTIONS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].commandNo", hasItem(eventId + "-C1")));

        // 时间窗过滤：远未来窗口内无曲线点（结构缺口列不受窗口影响）
        mockMvc.perform(get(EXECUTIONS_URL).param("eventId", eventId)
                        .param("windowStart", "2099-01-01T00:00:00")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].curve", hasSize(0)));
    }

    // ---------- 零目标：绝对偏差 ----------

    @Test
    void zeroTargetScenarioAlertsWithAbsoluteDeviationBasis() throws Exception {
        JsonNode run = runDemo(platformToken(), "ZERO_TARGET", null);
        JsonNode evaluation = run.get("evaluations").get(0);
        assertEquals(0.0, evaluation.get("targetPowerKw").asDouble(), "零目标合法（停机/回原状）");

        JsonNode alert = run.get("alerts").get(0);
        assertEquals("DIRECTIONAL_DEVIATION", alert.get("type").asText());
        assertEquals("ABSOLUTE_ZERO_TARGET", alert.get("basis").asText(),
                "零目标必须按绝对偏差判定（比例偏差除零无定义）");
        assertTrue(alert.get("deviationKw").asDouble() > 5.0,
                "停机目标下仍有约 8 kW 实际功率，绝对偏差超容差");

        // 告警经查询接口可见（幂等扫描不产生重复告警）
        String eventId = run.get("eventId").asText();
        mockMvc.perform(get(ALERTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].basis", hasItem("ABSOLUTE_ZERO_TARGET")));
    }

    // ---------- 方向性告警：两方向 ----------

    @Test
    void directionalAlertsRaisedForBothDirections() throws Exception {
        JsonNode peakRun = runDemo(platformToken(), "PEAK_BIAS", null);
        JsonNode peakAlert = peakRun.get("alerts").get(0);
        assertEquals("PEAK_SHAVE", peakAlert.get("direction").asText());
        assertEquals("DIRECTIONAL", peakAlert.get("basis").asText());
        assertTrue(peakAlert.get("deviationKw").asDouble() > 5.0, "削峰压不下来：实测高于目标");
        assertFalse(peakRun.get("evaluations").get(0).get("attained").asBoolean());

        JsonNode valleyRun = runDemo(platformToken(), "VALLEY_BIAS", null);
        JsonNode valleyAlert = valleyRun.get("alerts").get(0);
        assertEquals("VALLEY_FILL", valleyAlert.get("direction").asText());
        assertTrue(valleyAlert.get("deviationKw").asDouble() < -5.0, "填谷抬不上去：实测低于目标");
    }

    // ---------- 质量注入：迟到/缺口/重复/乱序在视图显式呈现 ----------

    @Test
    void lateStreamVisibleAndNotEligibleAsEvidence() throws Exception {
        JsonNode run = runDemo(platformToken(), "LATE_STREAM", null);
        JsonNode evaluation = run.get("evaluations").get(0);
        assertFalse(evaluation.get("attained").asBoolean(), "迟到样点不充当达标证明");
        assertTrue(evaluation.get("receiptReceived").asBoolean(), "回执环照常：受理≠到位");

        int latePoints = 0;
        for (JsonNode point : evaluation.get("curve")) {
            for (JsonNode mark : point.get("qualityMarks")) {
                if ("LATE".equals(mark.asText())) {
                    latePoints++;
                }
            }
        }
        assertTrue(latePoints >= 10, "迟到标记必须在曲线视图显式呈现: " + latePoints);
    }

    @Test
    void missingPointsRegisteredAsGapsWithoutZeroFill() throws Exception {
        JsonNode run = runDemo(platformToken(), "MISSING_POINTS", null);
        JsonNode evaluation = run.get("evaluations").get(0);
        JsonNode gaps = evaluation.get("gaps");
        assertEquals(1, gaps.size(), "序号 5、6 缺失登记为一个缺口段");
        assertEquals(5, gaps.get(0).get("fromSeq").asInt());
        assertEquals(6, gaps.get(0).get("toSeq").asInt());
        assertEquals(2, gaps.get(0).get("missingCount").asInt());

        JsonNode curve = evaluation.get("curve");
        assertEquals(11, curve.size(), "13 个样点位缺 2 个，缺失不补 0");
        for (JsonNode point : curve) {
            assertTrue(point.get("seq").asLong() != 5 && point.get("seq").asLong() != 6,
                    "缺口序号不得出现合成样点");
        }
        assertTrue(run.get("gapsDetected").asInt() >= 2, "两桩各缺 2 点");
    }

    @Test
    void duplicateAndOutOfOrderMarksVisibleWithoutMisjudgement() throws Exception {
        JsonNode duplicateRun = runDemo(platformToken(), "DUPLICATE_POINTS", null);
        JsonNode duplicateCurve = duplicateRun.get("evaluations").get(0).get("curve");
        int duplicates = countMark(duplicateCurve, "DUPLICATE");
        assertTrue(duplicates >= 3, "重复副本必须显式标记: " + duplicates);
        assertTrue(duplicateRun.get("evaluations").get(0).get("attained").asBoolean(),
                "重复副本不误判：原样点证据充足仍达标");

        JsonNode outOfOrderRun = runDemo(platformToken(), "OUT_OF_ORDER_POINTS", null);
        JsonNode outOfOrderCurve = outOfOrderRun.get("evaluations").get(0).get("curve");
        int outOfOrder = countMark(outOfOrderCurve, "OUT_OF_ORDER");
        assertTrue(outOfOrder >= 1, "乱序样点必须显式标记: " + outOfOrder);
    }

    // ---------- 失联进入核查状态 ----------

    @Test
    void offlineScenarioGoesToInvestigationWithoutFailureAssertion() throws Exception {
        JsonNode run = runDemo(platformToken(), "OFFLINE", null);
        assertEquals(0, run.get("samplesIngested").asInt(), "失联遥测断流");
        JsonNode evaluation = run.get("evaluations").get(0);
        assertEquals("NO_RECEIPT", evaluation.get("commandState").asText());
        assertFalse(evaluation.get("receiptReceived").asBoolean());
        assertFalse(evaluation.get("attained").asBoolean());
        assertEquals("OFFLINE_INVESTIGATION", evaluation.get("checkStatus").asText(),
                "失联进入核查状态，不直接断言设备未执行");

        JsonNode alert = run.get("alerts").get(0);
        assertEquals("OFFLINE_INVESTIGATION", alert.get("type").asText());
        assertEquals("OPEN", alert.get("state").asText());
        assertTrue(alert.get("evidence").isNull(), "失联无样点证据");
        assertTrue(alert.get("deviationKw").isNull(), "失联无偏差口径");
    }

    // ---------- 告警确认：留审计、幂等、数据范围 ----------

    @Test
    void alertConfirmLeavesAuditAndIsIdempotent() throws Exception {
        String platformToken = platformToken();
        JsonNode run = runDemo(platformToken, "PEAK_BIAS", null);
        String alertId = run.get("alerts").get(0).get("alertId").asText();

        MvcResult confirmed = mockMvc.perform(post(ALERTS_URL + "/" + alertId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedBy").value("platform_admin"))
                .andReturn();
        String confirmedAt = readTree(confirmed).get("confirmedAt").asText();

        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ALERT_CONFIRM' && @.result=='SUCCESS' "
                        + "&& @.targetId=='" + alertId + "')]", hasSize(1)));

        // 重复确认幂等：首条记录为准，无二次审计
        mockMvc.perform(post(ALERTS_URL + "/" + alertId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").value(confirmedAt));
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ALERT_CONFIRM' && @.targetId=='" + alertId + "')]",
                        hasSize(1)));
    }

    @Test
    void alertConfirmOutOfScopeOrUnknownReturns404() throws Exception {
        String platformToken = platformToken();
        JsonNode run = runDemo(platformToken, "PEAK_BIAS", null);
        String alertId = run.get("alerts").get(0).get("alertId").asText();
        // 未登记与无权限口径一致：一律 404，不泄露存在性
        mockMvc.perform(post(ALERTS_URL + "/ALT-not-exists/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(ALERTS_URL + "/" + UUID.randomUUID() + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isNotFound());
    }

    // ---------- 数据范围与角色 ----------

    @Test
    void stationAndOperatorRolesQueryWithinBoundStations() throws Exception {
        // 启动预置 + 本用例运行均为 cs-station-01：场站/运营商账号可见，平台全量
        JsonNode run = runDemo(platformToken(), "HEALTHY", null);
        String eventId = run.get("eventId").asText();

        mockMvc.perform(get(EXECUTIONS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].stationId", hasItem(STATION_ONE)));
        mockMvc.perform(get(ALERTS_URL).param("eventId", eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 场站参数越出本账号范围：查不到即空，不退化
        mockMvc.perform(get(EXECUTIONS_URL).param("eventId", eventId)
                        .param("stationId", "cs-station-02")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void unboundStationAccountSeesNothingAndRoleViolationRejected() throws Exception {
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound_mon", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-mon"),
                com.openvpp.common.context.RoleType.STATION_OPERATOR, DEMO_TENANT,
                Set.of(), "未绑定场站-监测演示账号"));
        String unboundToken = loginSuccess("station_unbound_mon").get("token").asText();
        mockMvc.perform(get(EXECUTIONS_URL).header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(ALERTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 越角色：演示编排仅平台；无凭证查询一律 401
        mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"HEALTHY\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(EXECUTIONS_URL)).andExpect(status().isUnauthorized());

        // 非法场景参数：400 且留拒绝审计
        mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"NO_SUCH\"}"))
                .andExpect(status().isBadRequest());
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

    /** 触发一次演示编排并返回响应体 */
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

    private static int countMark(JsonNode curve, String mark) {
        int count = 0;
        for (JsonNode point : curve) {
            for (JsonNode node : point.get("qualityMarks")) {
                if (mark.equals(node.asText())) {
                    count++;
                }
            }
        }
        return count;
    }
}

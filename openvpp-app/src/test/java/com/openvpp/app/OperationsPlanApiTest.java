package com.openvpp.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.app.orchestration.MonitoringDemoScenario;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.app.service.ChargePileDeviceYard;
import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分级派单接口集成测试（第 54 篇交付，3.5.5"派单可行性"主格 + "下发证据"格）：
 *
 * 覆盖验收口径：计划构建两级分配与绝对目标功率换算、缺口显性且确认被 409 拒绝、
 * 人工确认幂等（首条留痕为准，审计不重复）、派单下发受理回执与发送/回执时标分列、
 * 重复下发幂等（指令不重发、时标不覆盖）、失联指令进核查（NO_RECEIPT，禁止直接重发）、
 * 演示编排全链路（含 OFFLINE 场景与填谷方向换算）、越角色 401、伪造 tenantId 无效、
 * 跨租户 404、数据范围收窄（运营商/场站仅见所属场站参与的计划，未绑定账号空范围短路）。
 *
 * 时间口径：运营时间经 Clock Bean 取值，本类以 @MockBean 替换 —— 默认回真实时钟，
 * "申报截止后派单"用例重新打桩把时钟推进到截止之后（不 sleep、无 flake）。
 * 全部事件按 UUID 隔离；演示快照 demo-v1 由 ChargeDemoFixture 启动装配。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsPlanApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String EVENTS_URL = "/api/v1/operations/events";
    private static final String DECLARATIONS_URL = "/api/v1/operations/declarations";
    private static final String PLANS_URL = "/api/v1/operations/plans";
    private static final String COMMANDS_URL = "/api/v1/operations/commands";
    private static final String DEMO_RUNS_URL = "/api/v1/operations/plans/demo-runs";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;
    private static final String DEMO_TENANT = AccountRepository.DEMO_TENANT;
    private static final String STATION_ONE = "cs-station-01";
    private static final String STATION_TWO = "cs-station-02";
    /** 演示场站级快照资源标识（ChargeDemoFixture 装配，站级聚合充电桩组） */
    private static final String RESOURCE_ONE = "ev-cs-station-01";
    private static final String RESOURCE_TWO = "ev-cs-station-02";
    private static final String DEMO_SNAPSHOT_VERSION = "demo-v1";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private ChargePileDeviceYard deviceYard;

    /** 运营时钟替身：默认回真实时钟，个别用例重新打桩推进时间验证"截止后派单" */
    @MockBean
    private Clock clock;

    @BeforeEach
    void stubClockToRealTime() {
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    // ---------- 计划构建：两级分配 / 目标功率换算 / 缺口显性 ----------

    @Test
    void planBuildsTwoLevelsWithTargetsAndExplicitGap() throws Exception {
        String platformToken = platformToken();
        // 事件 A：两站全量申报（600+180=780），目标 780 → 缺口 0
        String eventA = "EVT-plan-a-" + UUID.randomUUID();
        publishEvent(platformToken, eventA, "PEAK_SHAVE", "780", 60, 120, 5);
        authorizeEvent(platformToken, eventA, STATION_ONE, STATION_TWO);
        declareAndConfirm(eventA, STATION_ONE, "600");
        declareAndConfirm(eventA, STATION_TWO, "180");

        // 事件 B：仅场站一申报 600，目标 900 → 缺口 300 显性
        String eventB = "EVT-plan-b-" + UUID.randomUUID();
        publishEvent(platformToken, eventB, "PEAK_SHAVE", "900", 180, 240, 5);
        authorizeEvent(platformToken, eventB, STATION_ONE);
        declareAndConfirm(eventB, STATION_ONE, "600");

        // 申报未截止即派单：409（截止守门含时刻本身，推进时钟前不得分配）
        mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventA + "\"}"))
                .andExpect(status().isConflict());

        // 推进时钟到截止之后：计划 A 两级分配成立
        advanceClockSeconds(400);
        MvcResult builtA = mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventA + "\",\"tenantId\":\"tenant-forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventA))
                .andExpect(jsonPath("$.organizerTenantId").value(DEMO_TENANT))
                .andExpect(jsonPath("$.level").value("PLATFORM_TO_OPERATOR"))
                .andExpect(jsonPath("$.gapKw").value(0.0))
                .andExpect(jsonPath("$.dispatchable").value(false))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].subjectId").value(DEMO_TENANT))
                .andExpect(jsonPath("$.children", hasSize(1)))
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andReturn();
        JsonNode planA = readTree(builtA);
        assertEquals(780.0, planA.get("adjustKw").asDouble(), 1e-9, "顶层目标 = 事件目标调节量");
        String planAId = planA.get("planId").asText();
        JsonNode child = planA.get("children").get(0);
        String childAId = child.get("planId").asText();
        assertEquals(planAId, child.get("parentPlanId").asText(), "子计划挂接顶层计划");
        assertEquals("OPERATOR_TO_PILE", child.get("level").asText());
        assertEquals(780.0, child.get("adjustKw").asDouble(), 1e-9);

        // 子计划两行：调节量与绝对目标功率分列（削峰 target = baseline − adjust）
        mockMvc.perform(get(PLANS_URL + "/" + childAId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[?(@.subjectId=='" + RESOURCE_ONE + "')].adjustKw")
                        .value(hasItem(600.0)))
                .andExpect(jsonPath("$.lines[?(@.subjectId=='" + RESOURCE_ONE + "')].targetPowerKw")
                        .value(hasItem(300.0)))
                .andExpect(jsonPath("$.lines[?(@.subjectId=='" + RESOURCE_TWO + "')].adjustKw")
                        .value(hasItem(180.0)))
                .andExpect(jsonPath("$.lines[?(@.subjectId=='" + RESOURCE_TWO + "')].targetPowerKw")
                        .value(hasItem(120.0)));

        // 计划 B：缺口 300 显性、不可确认
        MvcResult builtB = mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchable").value(false))
                .andReturn();
        JsonNode planB = readTree(builtB);
        assertEquals(300.0, planB.get("gapKw").asDouble(), 1e-9, "缺口显性：目标 900 − 有效容量 600");
        String planBId = planB.get("planId").asText();

        // 构建留审计（SUCCESS，对象为计划标识）
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='PLAN_BUILD' && @.result=='SUCCESS'"
                        + " && @.targetId=='" + planAId + "')]", hasSize(1)));

        // 缺口守门：缺口 > 0 的计划确认被 409 拒绝
        mockMvc.perform(post(PLANS_URL + "/" + planBId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isConflict());
        // 未确认即下发同样 409（半下发守门）
        mockMvc.perform(post(PLANS_URL + "/" + planBId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isConflict());

        // 构建留审计的 REJECTED 口径：缺口确认拒绝留痕
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='PLAN_CONFIRM' && @.result=='REJECTED'"
                        + " && @.targetId=='" + planBId + "')]", hasSize(1)));
    }

    // ---------- 确认幂等 + 下发幂等与回执证据 ----------

    @Test
    void confirmIdempotentAndSendIdempotentWithReceiptEvidence() throws Exception {
        String platformToken = platformToken();
        String operatorToken = operatorToken();
        String eventId = "EVT-plan-c-" + UUID.randomUUID();
        publishEvent(platformToken, eventId, "PEAK_SHAVE", "780", 60, 120, 5);
        authorizeEvent(platformToken, eventId, STATION_ONE, STATION_TWO);
        declareAndConfirm(eventId, STATION_ONE, "600");
        declareAndConfirm(eventId, STATION_TWO, "180");
        advanceClockSeconds(400);
        String planId = buildPlan(platformToken, eventId);
        String childId = childPlanId(platformToken, planId);

        // 人工确认（运营商）：顶层确认联动整树
        MvcResult confirmed = mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchable").value(true))
                .andExpect(jsonPath("$.confirmedBy").value("operator_admin"))
                .andReturn();
        String confirmedAt = readTree(confirmed).get("confirmedAt").asText();

        // 重复确认幂等：首条留痕为准，审计不重复
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedAt").value(confirmedAt));
        mockMvc.perform(get(PLANS_URL + "/" + childId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedBy").value("operator_admin"));
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='PLAN_CONFIRM' && @.result=='SUCCESS'"
                        + " && @.targetId=='" + planId + "')]", hasSize(1)));

        // 派单下发：全部受理，发送/回执时标分列
        MvcResult sent = mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.commandCount").value(2))
                .andExpect(jsonPath("$.summary.acked").value(2))
                .andExpect(jsonPath("$.summary.noReceipt").value(0))
                .andReturn();
        JsonNode sendBody = readTree(sent);
        // 发送/回执证据留痕在二级（运营商→桩）计划层；顶层计划分配结果不经通道，证据列为空
        assertTrue(sendBody.get("plan").get("sentAt").isNull(), "顶层计划不留发送证据");
        JsonNode firstChild = sendBody.get("plan").get("children").get(0);
        String childSentAt = firstChild.get("sentAt").asText();
        String childAckedAt = firstChild.get("ackedAt").asText();
        assertTrue(childAckedAt != null && !childAckedAt.isEmpty(), "全部受理才记计划回执");

        // 模拟设备按绝对目标功率生效（受理 ≠ 到位的口径由遥测工序判定，本链只到回执）
        assertEquals(300.0, deviceYard.currentTargetKw(RESOURCE_ONE), 1e-9);
        assertEquals(120.0, deviceYard.currentTargetKw(RESOURCE_TWO), 1e-9);

        // 重复下发幂等：指令不重发、发送/回执时标不覆盖
        MvcResult resent = mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.commandCount").value(2))
                .andExpect(jsonPath("$.plan.children[0].sentAt").value(childSentAt))
                .andExpect(jsonPath("$.plan.children[0].ackedAt").value(childAckedAt))
                .andReturn();
        JsonNode resendCommands = readTree(resent).get("commands");
        JsonNode firstCommands = sendBody.get("commands");
        for (int i = 0; i < 2; i++) {
            assertEquals(firstCommands.get(i).get("commandNo").asText(),
                    resendCommands.get(i).get("commandNo").asText(), "重试复用同批指令编号");
            assertEquals(firstCommands.get(i).get("receiptAckedAtMs").asLong(),
                    resendCommands.get(i).get("receiptAckedAtMs").asLong(), "回执时标不覆盖");
        }
        mockMvc.perform(get(AUDIT_LOGS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='PLAN_SEND' && @.result=='SUCCESS'"
                        + " && @.targetId=='" + planId + "')]", hasSize(2)));

        // 指令证据查询：状态/发送/回执时标分列
        mockMvc.perform(get(COMMANDS_URL).param("planId", childId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.state=='ACKED' && @.resourceId=='" + RESOURCE_ONE
                        + "' && @.targetPowerKw==300.0)].receiptStatus").value(hasItem("ACCEPTED")))
                .andExpect(jsonPath("$[?(@.state=='ACKED')].receiptAckedAtMs").isNotEmpty());
        // 单指令视角：发送时标（平台侧）与回执时标（设备侧）分列留痕
        mockMvc.perform(get(COMMANDS_URL).param("planId", childId).param("resourceId", RESOURCE_ONE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sentAt").isNotEmpty())
                .andExpect(jsonPath("$[0].receiptAckedAtMs").isNumber());
        // 状态过滤
        mockMvc.perform(get(COMMANDS_URL).param("planId", childId).param("state", "NO_RECEIPT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        // 非法状态：400
        mockMvc.perform(get(COMMANDS_URL).param("state", "WRONG")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isBadRequest());
    }

    // ---------- 失联场景进核查 ----------

    @Test
    void offlinePileCommandEntersNoReceiptCheck() throws Exception {
        String platformToken = platformToken();
        String operatorToken = operatorToken();
        String eventId = "EVT-plan-d-" + UUID.randomUUID();
        publishEvent(platformToken, eventId, "PEAK_SHAVE", "780", 300, 360, 5);
        authorizeEvent(platformToken, eventId, STATION_ONE, STATION_TWO);
        declareAndConfirm(eventId, STATION_ONE, "600");
        declareAndConfirm(eventId, STATION_TWO, "180");
        advanceClockSeconds(400);
        String planId = buildPlan(platformToken, eventId);
        String childId = childPlanId(platformToken, planId);
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk());

        try {
            // 教学故障注入：场站二设备失联（下行无回执，不直接重发）
            deviceYard.applyProfile(RESOURCE_TWO, MonitoringDemoScenario.OFFLINE.profile());
            MvcResult sent = mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                            .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.acked").value(1))
                    .andExpect(jsonPath("$.summary.noReceipt").value(1))
                    .andReturn();
            // 子计划存在未回执指令：计划层回执列留空（受理 ≠ 整树回执）
            JsonNode children = readTree(sent).get("plan").get("children");
            assertEquals(1, children.size(), "演示租户单运营商子计划");
            assertTrue(children.get(0).get("ackedAt").isNull(), "未全部受理不回执");

            // 失联指令进入核查：状态 NO_RECEIPT、无回执时标、无回执结论
            mockMvc.perform(get(COMMANDS_URL).param("planId", childId).param("state", "NO_RECEIPT")
                            .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].resourceId").value(RESOURCE_TWO))
                    .andExpect(jsonPath("$[0].sentAt").isNotEmpty());
            JsonNode noReceipt = readTree(mockMvc.perform(get(COMMANDS_URL)
                            .param("planId", childId).param("state", "NO_RECEIPT")
                            .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                    .andExpect(status().isOk()).andReturn()).get(0);
            assertTrue(noReceipt.get("receiptAckedAtMs").isNull(), "无回执不填回执时标");
            assertTrue(noReceipt.get("receiptStatus").isNull(), "无回执不填回执结论");

            // 失联设备的受理指令仍齐备（失联 ≠ 全部失败）
            mockMvc.perform(get(COMMANDS_URL).param("planId", childId).param("state", "ACKED")
                            .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].resourceId").value(RESOURCE_ONE));
        } finally {
            deviceYard.resetProfile(RESOURCE_TWO);
        }
    }

    // ---------- 演示编排：全链路 / 失联场景 / 填谷方向 ----------

    @Test
    void demoRunCoversFullChainWithScenarios() throws Exception {
        String platformToken = platformToken();

        // 健康场景：全链路产物齐备（含响应前基线冻结）
        MvcResult healthy = mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("HEALTHY"))
                .andExpect(jsonPath("$.direction").value("PEAK_SHAVE"))
                .andExpect(jsonPath("$.gapKw").value(0.0))
                .andExpect(jsonPath("$.baselineFreezes").value(2))
                .andExpect(jsonPath("$.commands", hasSize(2)))
                .andReturn();
        JsonNode run = readTree(healthy);
        String planId = run.get("planId").asText();
        for (JsonNode command : run.get("commands")) {
            assertEquals("ACKED", command.get("state").asText());
        }
        // 演示计划可查（平台 tenantWide 可见），版本历史齐备
        mockMvc.perform(get(PLANS_URL + "/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.children", hasSize(1)));

        // 失联场景：一条指令无回执进入核查，编排复位后设备恢复
        MvcResult offline = mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"OFFLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("OFFLINE"))
                .andReturn();
        JsonNode offlineCommands = readTree(offline).get("commands");
        long noReceipt = 0;
        for (JsonNode command : offlineCommands) {
            if ("NO_RECEIPT".equals(command.get("state").asText())) {
                noReceipt++;
            }
        }
        assertEquals(1, noReceipt, "失联场景恰一条指令进入核查");
        assertTrue(deviceYard.listDevices().stream()
                        .noneMatch(device -> Boolean.TRUE.equals(device.get("offline"))),
                "编排 finally 复位设备档案，干净复跑");

        // 填谷方向：目标功率按 baseline + adjust 换算（场站一 900+800=1700）
        MvcResult valley = mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"VALLEY_FILL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("VALLEY_FILL"))
                .andExpect(jsonPath("$.commands[?(@.resourceId=='" + RESOURCE_ONE
                        + "')].targetPowerKw").value(hasItem(1700.0)))
                .andReturn();
        assertEquals(1700.0, deviceYard.currentTargetKw(RESOURCE_ONE), 1e-9);

        // 遥测类场景不属于派单链路：400 并给出指引
        mockMvc.perform(post(DEMO_RUNS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"PEAK_BIAS\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 越角色 / 未认证 ----------

    @Test
    void crossRoleAndAnonymousAccessRejected() throws Exception {
        String platformToken = platformToken();
        String eventId = "EVT-plan-e-" + UUID.randomUUID();
        publishEvent(platformToken, eventId, "PEAK_SHAVE", "780", 60, 120, 5);
        authorizeEvent(platformToken, eventId, STATION_ONE, STATION_TWO);
        declareAndConfirm(eventId, STATION_ONE, "600");
        declareAndConfirm(eventId, STATION_TWO, "180");
        advanceClockSeconds(400);
        String planId = buildPlan(platformToken, eventId);

        // 建计划仅平台：运营商/场站 401
        mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isUnauthorized());

        // 确认/下发仅运营商：平台/场站 401
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isUnauthorized());

        // 未认证：查询与写操作一律 401
        mockMvc.perform(get(PLANS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(COMMANDS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PLANS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 伪造 tenantId 无效 / 跨租户 404 ----------

    @Test
    void forgedTenantIgnoredAndCrossTenantRejected() throws Exception {
        String platformToken = platformToken();
        String eventId = "EVT-plan-f-" + UUID.randomUUID();
        publishEvent(platformToken, eventId, "PEAK_SHAVE", "780", 60, 120, 5);
        authorizeEvent(platformToken, eventId, STATION_ONE, STATION_TWO);
        declareAndConfirm(eventId, STATION_ONE, "600");
        declareAndConfirm(eventId, STATION_TWO, "180");
        advanceClockSeconds(400);
        // 请求体伪造 tenantId：组织方锚定一律取会话身份
        MvcResult built = mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\",\"tenantId\":\"tenant-forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerTenantId").value(DEMO_TENANT))
                .andReturn();
        String planId = readTree(built).get("planId").asText();

        // 第二租户平台账号：事件不可见 → 构建 404、清单为空、详情 404
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "platform_admin_t2", PasswordDigest.hash(DEMO_PASSWORD, "salt-t2-plan"),
                RoleType.PLATFORM_ADMIN, "tenant-other", Set.of(), "第二租户平台账号-派单演示"));
        String tenant2Token = loginSuccess("platform_admin_t2").get("token").asText();
        mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(PLANS_URL).header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(PLANS_URL + "/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(COMMANDS_URL).header(HttpHeaders.AUTHORIZATION, bearer(tenant2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 第二租户运营商：越权确认/下发 404（未登记与无权限口径一致）
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "operator_t2", PasswordDigest.hash(DEMO_PASSWORD, "salt-t2op-plan"),
                RoleType.OPERATOR, "tenant-other", Set.of("cs-station-x"), "第二租户运营商-派单演示"));
        String operatorT2Token = loginSuccess("operator_t2").get("token").asText();
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorT2Token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(PLANS_URL + "/" + planId + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorT2Token)))
                .andExpect(status().isNotFound());
    }

    // ---------- 数据范围收窄 ----------

    @Test
    void scopedRolesSeeOnlyPlansOfOwnStations() throws Exception {
        String platformToken = platformToken();
        String operatorToken = operatorToken();
        // 事件 G：仅场站二申报（180/180，缺口 0），与事件 H（仅场站一）做范围收窄对拍
        String eventG = "EVT-plan-g-" + UUID.randomUUID();
        publishEvent(platformToken, eventG, "PEAK_SHAVE", "180", 60, 120, 5);
        authorizeEvent(platformToken, eventG, STATION_TWO);
        declareAndConfirm(eventG, STATION_TWO, "180");
        String eventH = "EVT-plan-h-" + UUID.randomUUID();
        publishEvent(platformToken, eventH, "PEAK_SHAVE", "600", 180, 240, 5);
        authorizeEvent(platformToken, eventH, STATION_ONE);
        declareAndConfirm(eventH, STATION_ONE, "600");
        advanceClockSeconds(400);
        String planG = buildPlan(platformToken, eventG);
        String planH = buildPlan(platformToken, eventH);

        // 运营商（两站绑定）：两个事件计划均可见（顶层 + 子计划各 2 条）
        mockMvc.perform(get(PLANS_URL).param("eventId", eventG)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get(PLANS_URL).param("eventId", eventH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 场站一账号：场站二专属计划不可见（清单与详情口径一致）
        mockMvc.perform(get(PLANS_URL).param("eventId", eventG)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(PLANS_URL + "/" + planG)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(PLANS_URL).param("eventId", eventH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].planId", hasItem(planH)));

        // 未绑定场站账号：空范围短路（清单恒空，详情 404）
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound_plan", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-plan"),
                RoleType.STATION_OPERATOR, DEMO_TENANT, Set.of(), "未绑定场站-派单演示账号"));
        String unboundToken = loginSuccess("station_unbound_plan").get("token").asText();
        mockMvc.perform(get(PLANS_URL).header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(PLANS_URL + "/" + planH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(unboundToken)))
                .andExpect(status().isNotFound());

        // 指令证据同样按范围收窄：场站一账号只见本场站指令
        mockMvc.perform(post(PLANS_URL + "/" + planH + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post(PLANS_URL + "/" + planH + "/send")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get(COMMANDS_URL).param("planId", planH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].stationId").value(STATION_ONE));
        mockMvc.perform(get(COMMANDS_URL).param("planId", planG)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
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

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void advanceClockSeconds(long seconds) {
        when(clock.instant()).thenAnswer(invocation -> Instant.now().plusSeconds(seconds));
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    private static String nowPlusMinutes(long minutes) {
        return ISO.format(LocalDateTime.now().plusMinutes(minutes));
    }

    /** 建事件并发布（事件标识显式传入，互不干扰） */
    private void publishEvent(String platformToken, String eventId, String direction,
                              String targetKw, long windowStartMinutes, long windowEndMinutes,
                              long deadlineMinutes) throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\","
                                + "\"direction\":\"" + direction + "\","
                                + "\"windowStart\":\"" + nowPlusMinutes(windowStartMinutes) + "\","
                                + "\"windowEnd\":\"" + nowPlusMinutes(windowEndMinutes) + "\","
                                + "\"targetAdjustKw\":" + targetKw + ","
                                + "\"declareDeadline\":\"" + nowPlusMinutes(deadlineMinutes) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk());
    }

    private void authorizeEvent(String platformToken, String eventId, String... stationIds)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"stationIds\":[");
        for (int i = 0; i < stationIds.length; i++) {
            body.append(i == 0 ? "" : ",").append("\"").append(stationIds[i]).append("\"");
        }
        body.append("]}");
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk());
    }

    /** 场站申报 + 运营商确认（场站二由专用演示账号申报） */
    private void declareAndConfirm(String eventId, String stationId, String declaredKw) throws Exception {
        String requestId = "req-plan-" + UUID.randomUUID();
        String token = stationToken();
        if (STATION_TWO.equals(stationId)) {
            ensureStationTwoAccount();
            token = loginSuccess("station2_admin").get("token").asText();
        }
        MvcResult declared = mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\","
                                + "\"stationId\":\"" + stationId + "\","
                                + "\"declaredKw\":" + declaredKw + ","
                                + "\"snapshotVersion\":\"" + DEMO_SNAPSHOT_VERSION + "\","
                                + "\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String declarationId = readTree(declared).get("declarationId").asText();
        mockMvc.perform(post(DECLARATIONS_URL + "/" + declarationId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk());
    }

    /** 场站二演示账号（幂等：已存在即跳过） */
    private void ensureStationTwoAccount() {
        try {
            accountRepository.insert(new com.openvpp.app.auth.Account(
                    "station2_admin", PasswordDigest.hash(DEMO_PASSWORD, "salt-station2-plan"),
                    RoleType.STATION_OPERATOR, DEMO_TENANT, Set.of(STATION_TWO), "场站二-派单演示账号"));
        } catch (DuplicateKeyException e) {
            // 已存在即复用（同测试类多用例共享上下文）
        }
    }

    private String buildPlan(String platformToken, String eventId) throws Exception {
        MvcResult built = mockMvc.perform(post(PLANS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readTree(built).get("planId").asText();
    }

    private String childPlanId(String platformToken, String planId) throws Exception {
        JsonNode detail = readTree(mockMvc.perform(get(PLANS_URL + "/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk()).andReturn());
        return detail.get("children").get(0).get("planId").asText();
    }
}

package com.openvpp.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.app.auth.PasswordDigest;
import com.openvpp.app.persistence.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 充电桩需求响应事件/申报/快照接口集成测试（第 53 篇交付，3.5.3 契约第 3~4 行）：
 *
 * 覆盖验收口径：平台建事件并发布、参与授权与版本递增、场站申报全链路（申报 → 运营商确认）、
 * 截止后拒绝（含时刻本身）、超可信容量拒绝、重复请求标识幂等返回同一张单、撤回释放预占、
 * 结束事件拒绝申报、越角色访问 401、伪造 tenantId 无效、未发布事件对场站不可见（404）、
 * 空范围账号查不到数据、平台登记新快照版本并供申报引用、拒绝留审计（REJECTED）。
 *
 * 时间口径：运营时间经 Clock Bean 取值，本类以 @MockBean 替换——默认回真实时钟，
 * "截止后拒绝"/"快照过期"两个用例重新打桩把时钟推进到未来时刻（不 sleep、无 flake）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class OperationsChargeDrApiTest {

    private static final String LOGIN_URL = "/api/v1/operations/auth/login";
    private static final String EVENTS_URL = "/api/v1/operations/events";
    private static final String DECLARATIONS_URL = "/api/v1/operations/declarations";
    private static final String SNAPSHOTS_URL = "/api/v1/operations/capability-snapshots";
    private static final String AUDIT_LOGS_URL = "/api/v1/operations/audit-logs";
    private static final String DEMO_PASSWORD = AccountRepository.DEMO_PASSWORD;
    private static final String DEMO_TENANT = AccountRepository.DEMO_TENANT;
    private static final String STATION_ONE = "cs-station-01";
    private static final String STATION_TWO = "cs-station-02";
    private static final String DEMO_SNAPSHOT_VERSION = "demo-v1";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    /** 运营时钟替身：默认回真实时钟，个别用例重新打桩推进时间验证时间边界 */
    @MockBean
    private Clock clock;

    @BeforeEach
    void stubClockToRealTime() {
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
    }

    // ---------- 平台建事件 → 发布 → 授权 → 查询 ----------

    @Test
    void platformCreatesPublishesAuthorizesAndQueriesEvent() throws Exception {
        String platformToken = platformToken();
        // 请求体伪造 tenantId：服务端一律取会话租户，伪造值不得影响组织方锚定
        MvcResult created = mockMvc.perform(post(EVENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("PEAK_SHAVE", nowPlusMinutes(60), nowPlusMinutes(120),
                                "500", nowPlusMinutes(30), "\"tenantId\":\"tenant-forged\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerTenantId").value(DEMO_TENANT))
                .andExpect(jsonPath("$.direction").value("PEAK_SHAVE"))
                .andExpect(jsonPath("$.lifecycle").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String eventId = readTree(created).get("eventId").asText();

        // 平台清单可见（tenantWide：本租户含未发布事件）
        mockMvc.perform(get(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventId", hasItem(eventId)));

        // 发布：CREATED → PUBLISHED
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));

        // 参与授权：两个演示场站纳入申报范围，版本递增到 2
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationIds\":[\"" + STATION_ONE + "\",\"" + STATION_TWO + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.authorizedStationIds", hasItem(STATION_ONE)))
                .andExpect(jsonPath("$.authorizedStationIds", hasItem(STATION_TWO)));

        // 场站/运营商经授权可见事件详情；时间状态为待响应（窗口未开始）
        mockMvc.perform(get(EVENTS_URL + "/" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.timeStatus").value("PENDING"));
        mockMvc.perform(get(EVENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventId", hasItem(eventId)));
    }

    @Test
    void unpublishedEventInvisibleToStation() throws Exception {
        String eventId = createEventOnly(platformToken());
        // 未发布事件对外不可见：清单查不到、详情 404（未登记与无权限口径一致）
        mockMvc.perform(get(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventId", not(hasItem(eventId))));
        mockMvc.perform(get(EVENTS_URL + "/" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("不在当前数据范围内")));
    }

    // ---------- 场站申报全链路 ----------

    @Test
    void stationDeclaresAndOperatorConfirms() throws Exception {
        String eventId = publishAndAuthorize(platformToken(), STATION_ONE);
        String requestId = "req-itest-" + UUID.randomUUID();

        // 场站提交申报：引用演示快照 demo-v1，削峰可信容量 600 kW → 申报 500 受理
        MvcResult declared = mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.tenantId").value(DEMO_TENANT))
                .andReturn();
        String declarationId = readTree(declared).get("declarationId").asText();

        // 运营商清单可见本范围内申报
        mockMvc.perform(get(DECLARATIONS_URL).header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].declarationId", hasItem(declarationId)));

        // 运营商确认：SUBMITTED → CONFIRMED
        mockMvc.perform(post(DECLARATIONS_URL + "/" + declarationId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 平台侧（tenantWide）同样可见；审计留痕确认动作 SUCCESS
        mockMvc.perform(get(DECLARATIONS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].declarationId", hasItem(declarationId)));
        JsonNode audits = readTree(mockMvc.perform(get(AUDIT_LOGS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken())))
                .andExpect(status().isOk()).andReturn());
        assertTrue(hasAuditRow(audits, "DECLARATION_CONFIRM", "SUCCESS", declarationId),
                "确认动作须留 SUCCESS 审计（对象 = 申报单号）");
    }

    @Test
    void declarationSubmittedAfterDeadlineRejected() throws Exception {
        Instant t0 = Instant.now();
        when(clock.instant()).thenReturn(t0);
        String platformToken = platformToken();
        String eventId = publishAndAuthorizeAt(platformToken, STATION_ONE, t0);

        // 时钟推进 2 小时：申报截止（t0+30 分钟）已过，截止守门含时刻本身 → 409 且原因明确
        when(clock.instant()).thenReturn(t0.plus(Duration.ofHours(2)));
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("已过申报截止时刻")));

        // 拒绝同样留审计（REJECTED），平台审计查询可见
        JsonNode audits = readTree(mockMvc.perform(get(AUDIT_LOGS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(hasAuditRow(audits, "DECLARATION_SUBMIT", "REJECTED", "已过申报截止时刻"),
                "截止拒绝须留 REJECTED 审计（原因落 detail）");
    }

    @Test
    void declarationOverCredibleCapacityRejected() throws Exception {
        String eventId = publishAndAuthorize(platformToken(), STATION_ONE);
        // cs-station-01 削峰可信容量 600 kW，申报 601 → 容量守门拒绝（409，原因含两口径数值）
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "601",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("申报容量超过可信容量")));
    }

    @Test
    void duplicateRequestIdReturnsSameDeclaration() throws Exception {
        String eventId = publishAndAuthorize(platformToken(), STATION_ONE);
        String requestId = "req-itest-" + UUID.randomUUID();
        String body = declarationBody(eventId, STATION_ONE, "300", DEMO_SNAPSHOT_VERSION, requestId);

        MvcResult first = mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String firstDeclarationId = readTree(first).get("declarationId").asText();

        // 同一 requestId 重复提交：返回原申报单，不重复校验、不重复占用
        MvcResult replay = mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declarationId").value(firstDeclarationId))
                .andReturn();
        assertEquals(readTree(first).get("declaredKw").asText(),
                readTree(replay).get("declaredKw").asText(), "幂等重放不得改写申报容量");

        // 新请求标识 + 剩余容量内（300 + 300 = 600 ≤ 600）→ 独立新单，预占台账守恒
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "300",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declarationId", not(firstDeclarationId)));
    }

    @Test
    void stationWithdrawsDeclarationAndOccupancyReleased() throws Exception {
        String eventId = publishAndAuthorize(platformToken(), STATION_ONE);
        String firstRequestId = "req-itest-" + UUID.randomUUID();
        MvcResult declared = mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, firstRequestId)))
                .andExpect(status().isOk()).andReturn();
        String declarationId = readTree(declared).get("declarationId").asText();

        // 场站撤回：SUBMITTED → WITHDRAWN，预占即释放
        mockMvc.perform(post(DECLARATIONS_URL + "/" + declarationId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        // 预占释放后同窗口再次申报（600 ≤ 600 上限）即受理——撤回不是简单拒绝
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "600",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void finishedEventRejectsDeclaration() throws Exception {
        String platformToken = platformToken();
        String eventId = publishAndAuthorize(platformToken, STATION_ONE);
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/finish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ENDED"));

        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("事件未发布或已结束")));
    }

    // ---------- 能力快照登记 ----------

    @Test
    void platformRegistersSnapshotAndDeclarationUsesIt() throws Exception {
        String platformToken = platformToken();
        String assessVersion = "itest-v-" + UUID.randomUUID();
        // 平台登记新评估版本：同容量口径换新会话指纹
        mockMvc.perform(post(SNAPSHOTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBody(STATION_ONE, "ev-cs-station-01", "2400", "900",
                                "600", "800", assessVersion, "fp-itest-new",
                                nowPlusMinutes(-5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value(STATION_ONE))
                .andExpect(jsonPath("$.assessVersion").value(assessVersion));

        // 同场站同版本重复登记即拒（版本不可覆盖）
        mockMvc.perform(post(SNAPSHOTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBody(STATION_ONE, "ev-cs-station-01", "2400", "900",
                                "600", "800", assessVersion, "fp-itest-new",
                                nowPlusMinutes(-5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("能力快照版本已登记")));

        // 新登记即当前指纹来源：申报引用新版本受理；旧版本 demo-v1 因会话指纹变化失效
        String eventId = publishAndAuthorize(platformToken, STATION_ONE);
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                assessVersion, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isOk());
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("充电会话已变化")));

        // 恢复该场站演示指纹（登记带原指纹的新版本即成为当前指纹来源），
        // 避免本用例的指纹变更污染同上下文其他用例对 demo-v1 的引用
        String restoreVersion = "itest-restore-" + UUID.randomUUID();
        mockMvc.perform(post(SNAPSHOTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBody(STATION_ONE, "ev-cs-station-01", "2400", "900",
                                "600", "800", restoreVersion, "fp-cs01-sess-a",
                                nowPlusMinutes(-5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessVersion").value(restoreVersion));
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "100",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value(DEMO_SNAPSHOT_VERSION));
    }

    // ---------- 服务端边界：越角色 / 伪造租户 / 空范围 ----------

    @Test
    void crossRoleAccessDenied() throws Exception {
        // 建/发/授权仅平台：场站与运营商一律 401（服务端 @RequireRole 拦截）
        mockMvc.perform(post(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("PEAK_SHAVE", nowPlusMinutes(60), nowPlusMinutes(120),
                                "500", nowPlusMinutes(30), "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("当前角色无权执行该操作")));
        mockMvc.perform(post(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(operatorToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("PEAK_SHAVE", nowPlusMinutes(60), nowPlusMinutes(120),
                                "500", nowPlusMinutes(30), "")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(SNAPSHOTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotBody(STATION_ONE, "ev-cs-station-01", "2400", "900",
                                "600", "800", "itest-x-" + UUID.randomUUID(), "fp-x",
                                nowPlusMinutes(-5))))
                .andExpect(status().isUnauthorized());

        // 申报提交仅场站：平台/运营商 token 一律 401
        mockMvc.perform(post(DECLARATIONS_URL).header(HttpHeaders.AUTHORIZATION, bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody("EVT-none", STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        // 确认仅运营商、撤回仅场站：互相越权一律 401
        mockMvc.perform(post(DECLARATIONS_URL + "/none/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(DECLARATIONS_URL + "/none/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken())))
                .andExpect(status().isUnauthorized());

        // 无会话凭证：401
        mockMvc.perform(get(EVENTS_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTenantIdInDeclarationBodyIgnored() throws Exception {
        String eventId = publishAndAuthorize(platformToken(), STATION_ONE);
        // 请求体伪造 tenantId：申报锚定租户恒为会话租户，伪造值不得落单
        mockMvc.perform(post(DECLARATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stationToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationBody(eventId, STATION_ONE, "500",
                                DEMO_SNAPSHOT_VERSION, "req-itest-" + UUID.randomUUID())
                                .replace("{", "{\"tenantId\":\"tenant-forged\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(DEMO_TENANT));
    }

    @Test
    void unboundStationAccountSeesNothing() throws Exception {
        // 无绑定场站账号：数据范围为空 → 事件/申报查询短路返回空，禁止退化为全量
        accountRepository.insert(new com.openvpp.app.auth.Account(
                "station_unbound_dr", PasswordDigest.hash(DEMO_PASSWORD, "salt-unbound-dr"),
                com.openvpp.common.context.RoleType.STATION_OPERATOR, DEMO_TENANT,
                Set.of(), "未绑定场站-演示账号"));
        String token = loginSuccess("station_unbound_dr").get("token").asText();
        mockMvc.perform(get(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(DECLARATIONS_URL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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

    private static String nowPlusMinutes(long minutes) {
        return ISO.format(LocalDateTime.now().plusMinutes(minutes));
    }

    /** 建事件请求体：extraFields 插在最前（供伪造 tenantId 等用例注入无关字段） */
    private static String eventBody(String direction, String windowStart, String windowEnd,
                                    String targetKw, String declareDeadline, String extraFields) {
        return "{"
                + (extraFields == null || extraFields.isEmpty() ? "" : extraFields)
                + "\"direction\":\"" + direction + "\","
                + "\"windowStart\":\"" + windowStart + "\","
                + "\"windowEnd\":\"" + windowEnd + "\","
                + "\"targetAdjustKw\":" + targetKw + ","
                + "\"declareDeadline\":\"" + declareDeadline + "\"}";
    }

    private static String declarationBody(String eventId, String stationId, String declaredKw,
                                          String snapshotVersion, String requestId) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"stationId\":\"" + stationId + "\","
                + "\"declaredKw\":" + declaredKw + ","
                + "\"snapshotVersion\":\"" + snapshotVersion + "\","
                + "\"requestId\":\"" + requestId + "\"}";
    }

    private static String snapshotBody(String stationId, String resourceId, String ratedKw,
                                       String baselineKw, String crediblePeakKw, String credibleValleyKw,
                                       String assessVersion, String sessionFingerprint, String assessedAt) {
        return "{\"stationId\":\"" + stationId + "\","
                + "\"resourceId\":\"" + resourceId + "\","
                + "\"ratedPowerKw\":" + ratedKw + ","
                + "\"baselineKw\":" + baselineKw + ","
                + "\"crediblePeakKw\":" + crediblePeakKw + ","
                + "\"credibleValleyKw\":" + credibleValleyKw + ","
                + "\"assessVersion\":\"" + assessVersion + "\","
                + "\"assessedAt\":\"" + assessedAt + "\","
                + "\"sessionFingerprint\":\"" + sessionFingerprint + "\"}";
    }

    /** 建事件（不发布）并返回事件标识 */
    private String createEventOnly(String platformToken) throws Exception {
        MvcResult created = mockMvc.perform(post(EVENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("PEAK_SHAVE", nowPlusMinutes(60), nowPlusMinutes(120),
                                "500", nowPlusMinutes(30), "")))
                .andExpect(status().isOk())
                .andReturn();
        return readTree(created).get("eventId").asText();
    }

    /** 完整前置：建 → 发布 → 授权单一场站，返回事件标识（真实时钟口径） */
    private String publishAndAuthorize(String platformToken, String stationId) throws Exception {
        String eventId = createEventOnly(platformToken);
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationIds\":[\"" + stationId + "\"]}"))
                .andExpect(status().isOk());
        return eventId;
    }

    /** 截止守门用例的前置：全部动作锚定给定时刻 t0（截止 = t0+30 分钟） */
    private String publishAndAuthorizeAt(String platformToken, String stationId, Instant t0)
            throws Exception {
        LocalDateTime base = LocalDateTime.ofInstant(t0, ZoneId.systemDefault());
        String eventId = "EVT-itest-" + UUID.randomUUID();
        mockMvc.perform(post(EVENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"eventId\":\"" + eventId + "\","
                                + "\"direction\":\"PEAK_SHAVE\","
                                + "\"windowStart\":\"" + ISO.format(base.plusMinutes(60)) + "\","
                                + "\"windowEnd\":\"" + ISO.format(base.plusMinutes(120)) + "\","
                                + "\"targetAdjustKw\":500,"
                                + "\"declareDeadline\":\"" + ISO.format(base.plusMinutes(30)) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post(EVENTS_URL + "/" + eventId + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(platformToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationIds\":[\"" + stationId + "\"]}"))
                .andExpect(status().isOk());
        return eventId;
    }

    /** 审计行匹配：动作 + 结果 + detail/对象含给定关键词（对象与原因两个维度均可查） */
    private static boolean hasAuditRow(JsonNode audits, String action, String result, String keyword) {
        for (JsonNode row : audits) {
            if (action.equals(row.get("action").asText())
                    && result.equals(row.get("result").asText())) {
                JsonNode detail = row.get("detail");
                JsonNode targetId = row.get("targetId");
                if ((detail != null && detail.asText().contains(keyword))
                        || (targetId != null && targetId.asText().equals(keyword))) {
                    return true;
                }
            }
        }
        return false;
    }
}

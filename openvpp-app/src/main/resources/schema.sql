-- 园区需求响应贯穿案例：核心业务结果持久化（教学默认 H2 文件库，docker 交付切换 MySQL）
-- 关联标识：response_id 贯穿 任务→指令→基线→账单 四表，是争议核查与追溯的锚点。
-- 生产形态：MySQL/PostgreSQL 同构建表，见第 25 篇（DDL 兼容两库，仓库层按连接方言自适应）。

CREATE TABLE IF NOT EXISTS dr_task (
    response_id     VARCHAR(64) PRIMARY KEY,
    event_id        VARCHAR(64) NOT NULL,
    declared_kwh    DECIMAL(14,3),
    target_kw       DECIMAL(14,3),
    window_start    BIGINT,
    window_end      BIGINT,
    state           VARCHAR(16) NOT NULL,
    gap_kw          DECIMAL(14,3),
    created_ms      BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS dispatch_instruction (
    instruction_id  VARCHAR(64) PRIMARY KEY,
    response_id     VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(64) NOT NULL,
    command_kw      DECIMAL(14,3),
    state           VARCHAR(16) NOT NULL,
    sent_ms         BIGINT,
    reached_ms      BIGINT
);

CREATE TABLE IF NOT EXISTS baseline_record (
    response_id     VARCHAR(64) NOT NULL,
    point_index     INT NOT NULL,
    rule_version    VARCHAR(32) NOT NULL,
    baseline_kw     DECIMAL(14,3) NOT NULL,
    actual_kw       DECIMAL(14,3),
    computed_ms     BIGINT NOT NULL,
    PRIMARY KEY (response_id, point_index)
);

CREATE TABLE IF NOT EXISTS bill (
    response_id     VARCHAR(64) NOT NULL,
    subject         VARCHAR(64) NOT NULL,   -- 'PLATFORM' 或 用户名
    amount_yuan     DECIMAL(14,2) NOT NULL,
    bill_type       VARCHAR(16) NOT NULL,   -- SETTLE / PENALTY / SHARE / PLATFORM_CUT / CORRECTION
    bill_version    VARCHAR(8)  NOT NULL,   -- 账期版本：原始出账 V1，争议更正逐轮递增 V2/V3…
    memo            VARCHAR(255),
    created_ms      BIGINT NOT NULL,
    -- 版本列入主键：同一主体多轮更正独立留档互不覆盖，原始账单（V1）永不删除
    PRIMARY KEY (response_id, subject, bill_type, bill_version)
);

-- 争议更正请求留档：correction_request_id 是纠偏请求幂等键，唯一键拦截同键重复
-- 提交（含并发）——先占键再写更正账单，重复方据此返回原版本结果，不重复出账
CREATE TABLE IF NOT EXISTS dispute_correction (
    response_id            VARCHAR(64) NOT NULL,
    correction_request_id  VARCHAR(64) NOT NULL,
    bill_version           VARCHAR(8)  NOT NULL,
    corrected_actual_kw    DECIMAL(14,3) NOT NULL,
    diff_yuan              DECIMAL(14,2) NOT NULL,
    created_ms             BIGINT NOT NULL,
    PRIMARY KEY (response_id, correction_request_id)
);

-- 第 52 篇运营底座：教学登录账号（全部为虚构演示账号，密码仅存加盐散列、不落明文）。
-- 账号-场站绑定以本表自持（不依赖业务主体模型），场站名称等展示信息后续波次与资源侧联接。
CREATE TABLE IF NOT EXISTS sys_account (
    login           VARCHAR(64)  PRIMARY KEY,
    password_hash   VARCHAR(255) NOT NULL,   -- 格式 salt$hex，见 PasswordDigest
    role            VARCHAR(32)  NOT NULL,   -- RoleType：PLATFORM_ADMIN / OPERATOR / STATION_OPERATOR
    tenant_id       VARCHAR(64)  NOT NULL,
    -- 逗号分隔的场站编号清单；平台角色空串 = 租户内全场站（tenantWide 语义），
    -- 运营商/场站角色空串 = 无绑定场站，数据范围按空范围短路查询
    station_ids     VARCHAR(512) NOT NULL DEFAULT '',
    display_name    VARCHAR(64)  NOT NULL,
    created_ms      BIGINT       NOT NULL
);

-- 第 52 篇运营底座：操作审计留痕——每次关键操作记录账号、角色、动作、业务对象、版本、时间与结果。
-- 查询恒带租户条件（tenant_id），按身份收窄到账号维度由仓库层执行（见 AuditLogRepository）。
CREATE TABLE IF NOT EXISTS operation_audit (
    audit_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    role            VARCHAR(32) NOT NULL,
    action          VARCHAR(64) NOT NULL,   -- LOGIN / 后续篇业务动作
    target_type     VARCHAR(64),            -- ACCOUNT / EVENT / DECLARATION ...
    target_id       VARCHAR(128),           -- 业务对象标识
    target_version  VARCHAR(32) NOT NULL DEFAULT '-',
    result          VARCHAR(16) NOT NULL,   -- SUCCESS / REJECTED
    detail          VARCHAR(512),
    created_ms      BIGINT NOT NULL
);

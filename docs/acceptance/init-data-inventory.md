# 演示初始化数据清单（第 59 篇验收底座）

> 用途：「部署与恢复」验收格第一项——**初始化数据与健康检查**。
> 清单逐项对应 `POST /api/v1/system/health` 的 `fixtures` 计数字段，在线核对与本文档一致即通过。
> 所有数据全虚构（openvpp-* 命名），不对应任何真实企业、场站或设备。
> 初始化机制：应用启动 `@PostConstruct` 装配，全部幂等（已存在即跳过），重启/重建上下文均可安全执行。

## 1. 登录账号（`AccountRepository.seedDemoAccounts`，表 `sys_account`）

| 账号 | 角色 | 场站绑定 | 说明 |
|------|------|----------|------|
| `platform_admin` | PLATFORM_ADMIN | 空串 = 租户内全场站（tenantWide） | 平台运营-演示账号 |
| `operator_admin` | OPERATOR | `cs-station-01`、`cs-station-02` | 运营商-演示账号 |
| `station_admin` | STATION_OPERATOR | `cs-station-01` | 场站-演示账号 |

- 统一演示密码 `Openvpp@2026`（仅虚构教学账号使用）；库中仅存加盐散列（格式 `salt$hex`，见 `PasswordDigest`），盐固定仅便于教学复算。
- 三账号同属演示租户 `tenant-openvpp`；跨租户隔离场景属后续篇目。
- 种入口径 `insert-if-absent`（重复键即跳过）。

## 2. 业务主体档案（`ChargeDemoFixture`，表 `sys_account` 之外的内存注册中心）

| 对象 | 标识 | 内容 |
|------|------|------|
| 演示租户 | `tenant-openvpp` | 服务区域「华东/教学区」 |
| 演示运营主体 | `entity-openvpp-demo` | 主体类型「充电运营」 |
| 演示场站一 | `cs-station-01` | 20 桩 × 120 kW（openvpp 演示快充站-01） |
| 演示场站二 | `cs-station-02` | 12 桩 × 60 kW（openvpp 演示快充站-02） |

## 3. 能力快照（`ChargeDemoFixture`，评估版本 `demo-v1`，表外注册中心）

| 场站 | 资源标识 | 额定 kW | 基线 kW | 削峰可信 kW | 填谷可信 kW |
|------|----------|---------|---------|-------------|-------------|
| cs-station-01 | ev-cs-station-01 | 2400 | 900 | 600 | 800 |
| cs-station-02 | ev-cs-station-02 | 720 | 300 | 180 | 240 |

- 评估时间取启动时刻前推 30 分钟，配合默认 480 分钟有效期（`openvpp.charge.snapshot-ttl-minutes`），演示期内随时可申报。
- 场站申报按事件方向取对应可信容量列校验。

## 4. 启动演示运行（每次启动自动各跑一次，运行标识随机、历史不覆盖）

| 装配类 | 编排内容 | 可见入口 |
|--------|----------|----------|
| `PlanDispatchDemoFixture` | 健康场景分级派单编排一次 | `GET /api/v1/operations/plans`、`/commands`（需登录态） |
| `MonitoringDemoFixture` | 健康场景执行监测编排一次 | `GET /api/v1/operations/monitoring/executions`（需登录态） |

- 失联/偏差等故障场景由 `POST .../demo-runs` 按需触发，不预置。
- 装配口径尽力而为：测试替身上下文跳过装配仅告警，不阻断启动。

## 5. 库表结构（`schema.sql`，H2/MySQL 同构，启动时 `CREATE TABLE IF NOT EXISTS`）

| 表 | 用途 | 关键约束 |
|----|------|----------|
| `dr_task` | 响应任务（贯穿标识 response_id） | 主键 response_id = 同键并发原子认领锁 |
| `dispatch_instruction` | 下发指令记录 | 主键 instruction_id（VARCHAR(64)，派生编号上限由此约束） |
| `baseline_record` | 基线/实测点（响应期计量证据） | 主键 (response_id, point_index) |
| `bill` | 账单（SETTLE/PENALTY/SHARE/PLATFORM_CUT/CORRECTION） | 主键含 bill_version，更正版本独立留档互不覆盖 |
| `dispute_correction` | 纠偏请求留档 | 主键 (response_id, correction_request_id) = 纠偏请求幂等键 |
| `sys_account` | 教学登录账号 | 主键 login；种入物，演示重置不清 |
| `operation_audit` | 操作审计留痕 | 自增 audit_id；审计留痕，演示重置不清 |

## 6. 幂等守卫装配（`IdempotencyGuardConfig`）

| 形态 | 配置 | 行为 |
|------|------|------|
| 本地教学（默认 profile） | `openvpp.idempotency.redis-enabled` 缺省 | `NoopIdempotencyGuard` 直通，幂等由数据库唯一约束 + 事务内原子认领承担，零 Redis 依赖 |
| 容器交付（docker profile） | `redis-enabled=true`（`application-docker.yml`） | `RedisIdempotencyGuard`：结果缓存 TTL 30 分钟快速重放 + 在途标记 TTL 120 秒；Redis 任何故障按退化语义消化（读失败=未命中、登记失败=放行、写失敗=告警），正确性始终由数据库兜底 |

## 7. 镜像与配置版本核对（「初始化与镜像版本一致」）

| 组件 | docker-compose.yml | application-docker.yml / 代码注释 | 一致 |
|------|--------------------|-----------------------------------|:--:|
| MySQL | `mysql:8.0`（root/openvpp-demo，库 openvpp_demo） | 数据源 `${MYSQL_HOST:mysql}:3306`；pom 注释「compose 内 mysql:8.0」 | ✓ |
| Redis | `redis:7-alpine` | `spring.redis.host/port` 变量注入；连接/命令超时 2s | ✓ |
| EMQX | `emqx:5`（remote 网关模式才启用） | compose 环境变量开启 remote，默认 local 不触公网 | ✓ |
| 应用 | 本地构建 `Dockerfile`（maven:3.9-eclipse-temurin-11 构建 / eclipse-temurin:11-jre 运行，Java 11 全链一致） | openvpp-app jar | ✓ |

## 8. 在线核对入口

```bash
curl http://127.0.0.1:8080/api/v1/system/health
# data.fixtures 应回传：demoAccounts=3、demoStations=2、capabilitySnapshots=2
# data.components 应回传：db=UP、idempotencyGuard.type（本地 NoopIdempotencyGuard / 容器 RedisIdempotencyGuard）、gateway.mode
```

# 专题验收工程（专栏第 59 篇底座）——验收矩阵与证据索引

> 验收格：策划方案 3.5.5「部署与恢复」——H2 默认链路、MySQL + Redis 容器链路、
> Redis 不可用、进程中断、重置后复跑。
> 通过判据：初始化与镜像版本一致，数据库约束保证正确性，故障后可恢复，
> 重置同时清理数据库与运行态，原样命令有日志。
> 验收日期：2026-10-12（环境受限项注明原因）。所有命令原样留档于 `logs/`，
> 脚本为 `tools/acceptance/` 下同名可执行文件，可原样复跑。

## 验收矩阵

| # | 验收项 | 证据 | 结果 |
|---|--------|------|:--:|
| 1 | H2 默认链路（零外部依赖） | `logs/2026-10-12-clean-env.log`：新克隆目录（基线 b5d2d8f）+ 空 .m2 全量构建 + 应用启动 + 演示链路 7 项断言（净实收 1200.00 / 幂等重放 / 重置后全新执行） | ✅ 已覆盖 |
| 2 | MySQL + Redis 容器链路 | `logs/2026-10-12-fault-inject-redis.log`：docker compose 中间件 + 应用 docker profile，健康检查 UP（db=MySQL 8.0、redis=PONG、guard=RedisIdempotencyGuard）、闭环落 MySQL、结果缓存写 Redis | ✅ 已覆盖 |
| 3 | 初始化与镜像版本一致 | `init-data-inventory.md` 第 7 节核对表（compose mysql:8.0 / redis:7-alpine ↔ application-docker.yml ↔ pom 注释一致）；在线核对 `GET /api/v1/system/health` 的 fixtures 计数（3 账号 / 2 场站 / 2 快照） | ✅ 已覆盖 |
| 4 | Redis 不可用降级 | `logs/2026-10-12-fault-inject-redis.log` 步骤 2-4：`docker compose stop redis` 后 health=DEGRADED；新业务键 200 落库（数据库兜底）；同键重放不重复出账（缓存读取失败退化未命中 → 数据库路径） | ✅ 已覆盖 |
| 5 | 进程中断恢复 | 同上日志步骤 6：`kill -9` 应用 → 重启 → 同键幂等重放、金额一致、账单条数不增（幂等键与版本化仓库状态一致性） | ✅ 已覆盖 |
| 6 | 重置后复跑（同清数据库与运行态） | 同上日志步骤 7 + `logs/2026-10-12-smoke-reset.log`：`POST /api/v1/demo/reset` 清库表 + 内存台账 + Redis 命名空间；重置后同键全新执行；`reset-demo.sh --direct` 容器直清兜底形态同样验证通过 | ✅ 已覆盖 |
| 7 | 数据库约束保证正确性 | `init-data-inventory.md` 第 5 节约束表（response_id 主键=原子认领锁、账单主键含版本、纠偏请求唯一键）；并发回归 4 项全绿（见下） | ✅ 已覆盖 |
| 8 | 并发回归 | `logs/2026-10-12-concurrency.log`：申报并发占用守恒（ChargeDeclarationConcurrencyTest）、结算并发确认幂等（SettlementServiceTest#并发确认幂等_首条留痕为准）、贯穿案例 50 路并发唯一执行 + 12 路并发纠偏版本连续（ParkResponseConcurrencyTest） | ✅ 已覆盖 |
| 9 | 原样命令有日志 | 本目录 `logs/`（干净环境 / 故障注入 / 冒烟与重置 / 并发 / 全栈容器 / 最终全量测试），脚本 `tools/acceptance/*.sh` 可原样复跑 | ✅ 已覆盖 |
| 12 | 全量回归基线 | `logs/2026-10-12-mvn-test-final.log`：`mvn -s settings-openvpp.xml -pl openvpp-app -am test` BUILD SUCCESS（各模块测试全绿，含新增 SystemHealthControllerTest） | ✅ 已覆盖 |
| 10 | MySqlComposeIT（真实 MySQL 4 项） | 宿主 3306 被本机 MySQL80 占用且 root 密码与教学口令不符，无管理员权限停止服务；按既有门控自动跳过（`Tests run: 4, Skipped: 4`，日志见并发记录），标**未覆盖** | ❌ 未覆盖（环境受限，原因已写明） |
| 11 | EMQX remote 模式全栈四容器 | `logs/2026-10-12-compose-fullstack.log`：`docker compose up --build`（app 镜像容器内 maven 构建）四容器全就绪；health UP（**gateway.mode=remote**，EMQX 接入生效）；闭环 1200.00、同键重放 true、账单 5 条、结果缓存写 Redis 容器、EMQX 控制台 200；收尾 `docker compose down` | ✅ 已覆盖 |

## 交付物清单

| 类型 | 路径 | 说明 |
|------|------|------|
| 健康端点 | `openvpp-app/src/main/java/com/openvpp/app/controller/SystemHealthController.java` | `/api/v1/system/health`：db/redis/guard/gateway 组件探测 + fixtures 初始化核对 + 整体状态（UP/DEGRADED/DOWN） |
| 健康端点测试 | `openvpp-app/src/test/java/com/openvpp/app/SystemHealthControllerTest.java` | 本地形态组件状态与 fixtures 计数断言（1 项） |
| compose 增强 | `docker-compose.yml` | mysql 宿主端口参数化 `${MYSQL_HOST_PORT:-3306}`（默认行为不变；宿主端口被占的验收路径） |
| 验收脚本 | `tools/acceptance/smoke-demo.sh` | 演示链路冒烟（health/run/bills/重放/更正/重置复跑） |
| 验收脚本 | `tools/acceptance/reset-demo.sh` | 应用内重置 + `--direct` 容器直清兜底 |
| 验收脚本 | `tools/acceptance/fault-inject-redis.sh` | Redis 故障注入 + 进程中断恢复 + 重置复跑编排 |
| 验收脚本 | `tools/acceptance/concurrency-regression.sh` | 关键并发用例逐项复跑（容器 IT 自动探测可达性） |
| 验收脚本 | `tools/acceptance/clean-env-rerun.sh` | 新克隆 + 空 .m2 干净环境复跑 |
| 初始化清单 | `docs/acceptance/init-data-inventory.md` | 初始化数据逐项清单 + 镜像版本核对 + 在线核对入口 |
| 实跑记录 | `docs/acceptance/logs/*.log` | 2026-10-12 各脚本原样输出 |

## 环境说明

- 验收机：Windows + Docker Desktop 29.6.1（Docker Compose v5.2.0）、Java 11.0.26、Maven 3.9.9、Git Bash。
- 宿主 3306 被本机 MySQL80 占用（Manual 启动，无管理员权限停止，密码非教学口令）→ 容器 MySQL 以 `MYSQL_HOST_PORT=13306` 映射，应用/脚本以 `MYSQL_PORT=13306` 注入；compose 默认值不变，读者环境无此冲突时一切命令与文档原样成立。
- 宿主 6379 无冲突（Docker Desktop 端口转发即本工程 redis 容器）。

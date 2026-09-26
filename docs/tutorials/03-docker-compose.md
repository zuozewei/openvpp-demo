# 教程 03：Docker Compose 一键交付（第 25 篇）

> 目标：一条命令拉起应用 + MySQL + Redis + EMQX 四容器演示环境，
> 并完成真实 MySQL + Redis 的集成回归。

## 前置条件

- Docker 与 Docker Compose v2（`docker compose version` 可用）。

## 操作步骤

```bash
docker compose up --build
# 应用 http://localhost:8080（docker profile），EMQX 控制台 http://localhost:18083（admin/openvpp）
```

三个中间件都有真实业务落点（非摆设）：

| 中间件 | 业务落点 |
|--------|----------|
| MySQL | 任务/指令/基线/账单持久化（`application-docker.yml` 数据源；`schema.sql` 两库同构，仓库层按数据源 URL 自适应方言：H2 用 `MERGE INTO .. KEY`，MySQL 用 `INSERT .. ON DUPLICATE KEY UPDATE`） |
| Redis | 已完成结果的幂等快速重放（`IdempotencyGuard`，`openvpp.idempotency.redis-enabled=true` 启用；在途标记 TTL 120s、结果缓存 TTL 30min）。**正确性始终由数据库唯一约束 + 事务内原子认领兜底**，Redis 不可用时守卫内部自动退化为纯数据库路径（故障退化有单测覆盖） |
| EMQX | `openvpp.gateway.mode=remote` 的 MQTT 协议接入（订阅 `openvpp/+/telemetry|event|ack`，教学消费者打 `[INGRESS-MOCK]` 日志） |

依赖就绪采用 `depends_on` + `service_healthy` 健康检查（mysqladmin / redis-cli / emqx ctl），
应用不再"起了但连不上"。业务闭环的遥测/计量仍为编排层内置模拟源（口径见[教程 02](02-park-demand-response.md)）。

## 验证方法

```bash
curl http://localhost:8080/api/v1/system/ping          # status=UP
curl -X POST "http://localhost:8080/api/v1/demo/run?responseId=run-001&path=NORMAL"
curl "http://localhost:8080/api/v1/bills?responseId=run-001"   # 账单真实落 MySQL
```

数据落在 MySQL 容器（库名 `openvpp_demo`），可 `docker compose exec mysql mysql -uroot -popenvpp-demo openvpp_demo -e "select * from demo_bill;"` 直接核对。

## 真实环境回归（MySqlComposeIT，4 项）

```bash
docker compose up -d mysql redis
mvn -s settings-openvpp.xml -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false
```

覆盖：真实 MySQL 建表、提交后 Redis 缓存写入、同键 12 路并发认领与并发纠偏版本连续性、
重置清缓存后重新落库。宿主机 6379 被占用时以 `-Dopenvpp.it.redis-port=端口` 指定。

## 常见问题

- **镜像拉取失败（无法直连 Docker Hub）**：可经镜像源拉取后重打标准 tag，如
  `docker.m.daocloud.io/library/mysql:8.0` → `mysql:8.0`。
- **与单体 jar 冲突（8080 占用）**：先停掉教程 02 的进程，或修改 compose 端口映射。
- **想回到零依赖形态**：`docker compose down` 后按[教程 01](01-quick-start.md)启动即可，两种形态互不影响。

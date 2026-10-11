# 全栈四容器交付实跑记录（第 59 篇验收证据）

- 日期：2026-10-12
- 原始日志：`logs/2026-10-12-compose-fullstack.log`
- 命令：`MYSQL_HOST_PORT=13306 EMQX_DASHBOARD_HOST_PORT=28083 docker compose up --build -d` → 验证 → `docker compose down`

## 环境参数说明（默认值不变）

| 参数 | 默认值 | 本机实跑值 | 原因 |
|------|--------|-----------|------|
| `MYSQL_HOST_PORT` | 3306 | 13306 | 宿主 3306 被本机 MySQL80 占用且无法停止（无管理员权限） |
| `EMQX_DASHBOARD_HOST_PORT` | 18083 | 28083 | 18083 落入 Windows winnat 动态保留段（`netsh interface ipv4 show excludedportrange tcp` 实测 18063–18162），绑定被拒 |

compose 端口参数化为本次验收底座新增（`docker-compose.yml`），默认行为与既有文档完全一致。

## 实跑结果

| 步骤 | 预期 | 实际 | 判定 |
|------|------|------|:--:|
| `docker compose up --build -d` | app 镜像容器内 maven 构建（maven:3.9-eclipse-temurin-11 → eclipse-temurin:11-jre），四容器就绪 | openvpp-demo-app-1 Started；mysql/redis/emqx 均 Healthy | ✅ |
| health 组件 | db UP（MySQL 8.0.46）、redis UP（PONG）、guard=RedisIdempotencyGuard、**gateway.mode=remote** | 全部符合 | ✅ |
| 容器内闭环 | 净实收 1200.00 | 1200.00 | ✅ |
| 容器内幂等 | 同键重放 true、不重复出账 | true；账单 5 条 | ✅ |
| Redis 缓存在容器内 | `docker compose exec redis redis-cli --scan` 见结果键 | `openvpp:idempotency:run:stack-010806` | ✅ |
| EMQX 控制台 | HTTP 200 | 28083 → 200 | ✅ |
| 收尾 | `docker compose down` 全清 | 容器与网络移除 | ✅ |

## 结论

MySQL + Redis + EMQX + app 四容器交付形态实测成立；remote 网关模式（EMQX MQTT 接入）
在容器网络内生效；与应用侧 H2 默认形态互不冲突（down 后按教程 01 原样可跑）。

# 故障注入与恢复实跑记录（第 59 篇验收证据）

- 日期：2026-10-12
- 环境：Windows 11 · Docker Desktop 29.6.1 · Java 11.0.26 · Maven 3.9.9 · Git Bash
- 原始日志：`logs/2026-10-12-fault-inject-redis.log`（脚本原样输出）
- 复跑命令：`MYSQL_PORT=13306 bash tools/acceptance/fault-inject-redis.sh`
  （宿主 3306 被本机 MySQL80 占用，容器 MySQL 以 `MYSQL_HOST_PORT=13306` 映射，compose 默认行为不变）

## 形态

docker compose 中间件（mysql:8.0 + redis:7-alpine，宿主侧 13306/6379）+ 本地 jar 以 docker profile
启动（MySQL 持久化 + Redis 前置幂等启用）。健康端点 `/api/v1/system/health` 全程在线对拍。

## 步骤与判定（15 项断言全过）

| 步骤 | 操作 | 关键实际结果 | 判定 |
|------|------|--------------|:--:|
| 1 | run 键A 闭环（Redis 可用） | 净实收 1200.00；`redis-cli --scan` 确认结果缓存键 `openvpp:idempotency:run:fault-a-005300` 已写入 | ✅ |
| 2 | `docker compose stop redis` | health 整体转 **DEGRADED**：`redis.status=DOWN`（Lettuce 2 秒命令超时），`db.status=UP`（MySQL 8.0.46），应用可访问 | ✅ |
| 3 | Redis 不可用下 run 键B（新键） | 不 500，净实收 1200.00 落库——守卫退化语义（读失败=未命中/登记失败=放行），数据库兜底路径生效 | ✅ |
| 4 | Redis 不可用下 run 键A 同键 | `idempotentReplay=true`、金额一致、账单条数不变（V1 五条）——缓存读取失败退化未命中，数据库路径重放不重复出账 | ✅ |
| 5 | `docker compose start redis` | health 恢复 UP（redis PONG） | ✅ |
| 6 | `kill -9` 应用进程 → 重启 | 同键重放 `idempotentReplay=true`、金额 1200.00 一致、账单条数不增——进程中断后幂等键与版本化仓库状态一致性成立 | ✅ |
| 7 | `POST /api/v1/demo/reset` 后 run 键A | `idempotentReplay=false`（全新执行）、净实收 1200.00——重置同清库表 + 内存运行态 + Redis 幂等命名空间，重置后干净复跑 | ✅ |

## 结论

- Redis 不可用降级、进程中断恢复、重置后复跑三个验收子项全部实测通过；
- 正确性边界与代码口径一致：Redis 只做加速层，任何故障由数据库唯一约束 + 事务内原子认领兜底；
- 健康端点的 DEGRADED 语义（Redis 守卫启用但不可达）在真实故障注入下按设计呈现。

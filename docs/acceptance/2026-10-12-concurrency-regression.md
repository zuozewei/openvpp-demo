# 并发回归复跑记录（第 59 篇验收证据）

- 日期：2026-10-12
- 原始日志：`logs/2026-10-12-concurrency.log`
- 复跑脚本：`bash tools/acceptance/concurrency-regression.sh`

## 逐项结果

| 验收口径 | 用例 | 结果 |
|----------|------|:--:|
| 申报并发占用守恒 | `ChargeDeclarationConcurrencyTest`（openvpp-market）：十线程并发申报同场站同事件占用守恒 + 并发同请求标识仅受理一次不重复占用 | ✅ PASS |
| 结算并发确认幂等 | `SettlementServiceTest#并发确认幂等_首条留痕为准`（openvpp-settlement） | ✅ PASS |
| 贯穿案例并发幂等与版本化 | `ParkResponseConcurrencyTest`（openvpp-app，4 项）：同键 50 路并发仅一次完整执行、12 路并发纠偏 12 个连续版本不覆盖、同一纠偏请求并发 8 路仅一次出账、顺序重复提交返回原结果 | ✅ PASS（Tests run: 4, Failures: 0） |
| 真实容器并发 | `MySqlComposeIT`（openvpp-app，4 项） | ❌ 未覆盖——按既有门控跳过（`Skipped: 4`），原因见下 |

## MySqlComposeIT 未覆盖说明

该 IT 的 MySQL 地址口径固定 `127.0.0.1:3306` + 教学口令（`TestPropertySource` 硬编码，高于系统属性无法外部覆盖）。
验收机宿主 3306 被本机 MySQL80 实例占用且 root 密码与教学口令不符（已实测 `ERROR 1045`），
当前账号无管理员权限停止该系统服务（`Stop-Service` 返回拒绝访问）。
按类注释的既有门控：服务不可达时整类自动跳过、不计入失败——`Tests run: 4, Failures: 0, Errors: 0, Skipped: 4`，BUILD SUCCESS。
真实 MySQL 方言下的写入适配与并发正确性由同构 schema（`schema.sql` 两库同构）+ `ResponseRepositoryDialectTest`（方言自适应）+ 容器链路的故障注入实测（`logs/2026-10-12-fault-inject-redis.log`，真实 MySQL 8.0.46 落库与重放）共同支撑；
在可获得 3306 的环境（或停用宿主实例）下，执行 `docker compose up -d mysql redis` 后
`mvn -s settings-openvpp.xml -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false` 即补上本格。

## 结论

关键并发用例（申报占用守恒、结算确认幂等、贯穿案例并发幂等/版本化）逐项复跑全绿；
真实容器并发格显式标未覆盖并写明环境原因与补跑路径。

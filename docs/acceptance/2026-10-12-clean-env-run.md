# 干净环境复跑记录（第 59 篇验收证据）

- 日期：2026-10-12（任务启动 2026-10-11 深夜，实跑于 2026-10-12 凌晨）
- 环境：Windows 11 · Git Bash · Java 11.0.26 (LTS) · Apache Maven 3.9.9 · Docker Desktop 29.6.1
- 记录人：专栏交付子代理（openvpp-demo 工作树）
- 原始日志：`logs/2026-10-12-clean-env.log`（命令原样输出，未裁剪）
- 复跑脚本：`tools/acceptance/clean-env-rerun.sh`（可原样复现）

## 1. 命令与输入

```bash
# 驱动脚本内部依次执行：
git clone --depth 1 <本仓库> /c/Temp/openvpp-clean-verify    # 新克隆目录（已提交基线，不含未提交改动）
cd /c/Temp/openvpp-clean-verify
mvn -s settings-openvpp.xml -Dmaven.repo.local=C:/Temp/openvpp-clean-m2 install -DskipTests
java -jar openvpp-app/target/openvpp-app-1.0.0.jar \
  --spring.datasource.url="jdbc:h2:file:<克隆目录>/h2-data/openvpp-db;AUTO_SERVER=TRUE;LOCK_TIMEOUT=10000" \
  --server.port=8080
# 冒烟（脚本内 curl 断言，详见工具脚本）
```

口径说明：
- 「空 .m2 以外的最简环境」：本地 Maven 仓库指向**空目录** `C:/Temp/openvpp-clean-m2`，全部依赖经 `settings-openvpp.xml` 公共镜像下载，模拟新机器首跑；
- 「新克隆目录」：`--depth 1` 克隆 master 已提交基线 `b5d2d8f`，不含工作树未提交改动（本次新增的健康端点为未提交内容，故冒烟使用既有端点：ping → demo/run → bills → 同键重放 → reset 后复跑）；
- 数据源覆盖到克隆目录内，与宿主演示库（`~/.openvpp/openvpp-db`）完全隔离。

## 2. 预期 / 实际

| 步骤 | 预期 | 实际 | 判定 |
|------|------|------|:--:|
| 克隆 | HEAD = b5d2d8f | b5d2d8f【新增】运营分级派单接口与模拟设备装配 | ✅ |
| 空 .m2 全量构建 | BUILD SUCCESS，产出 openvpp-app jar | BUILD SUCCESS（依赖全量下载完成） | ✅ |
| 应用启动（默认本地形态） | /system/ping 返回 status=UP | UP | ✅ |
| demo/run（申报 600 kWh / 目标 900 kW） | 净实收 1200.00，首次非重放 | 1200.00 / false | ✅ |
| bills（V1） | 5 条（SETTLE + PLATFORM_CUT + SHARE×3） | 5 条 | ✅ |
| 同键重放 | idempotentReplay=true，金额一致 | true | ✅ |
| 任务表 | 同键唯一行 | 1 行 | ✅ |
| demo/reset 后同键复跑 | 全新执行（非重放），金额 1200.00 | false / 1200.00 | ✅ |

## 3. 结论

H2 默认链路在新克隆 + 空 .m2 的最简环境下**一条命令复跑全链路**成立；
「重置后一条命令复跑」的验收口径闭环（reset → 同键全新执行）。

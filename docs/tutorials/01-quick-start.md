# 教程 01：快速开始与零依赖启动

> 目标：完成首次构建，以单体 jar 形态零外部依赖启动，并验证健康检查。
> 断网环境可完整跑通本教程。

## 前置条件

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 11（编译目标锁定 `release=11`） | 高版本 JDK 编译时拒绝 Java 12+ API |
| Maven | 3.6+ | 构建与测试 |
| Docker | 可选 | 仅教程 03 需要 |

## 操作步骤

```bash
# 1. 构建（跳过测试；settings-openvpp.xml 显式走公共镜像，不改动全局 Maven 配置）
mvn -s settings-openvpp.xml install -DskipTests

# 2. 启动单体入口（openvpp-app 串联全部业务模块）
cd openvpp-app && mvn -s ../settings-openvpp.xml spring-boot:run
```

默认配置下：

- 数据落本地 H2 文件库（`~/.openvpp/openvpp-db`），无外部数据库依赖；
- 网关为**本地模拟模式**（`openvpp.gateway.mode=local`），不连接任何外部消息服务；
- 服务端口 `8080`。

## 验证方法

```bash
curl http://127.0.0.1:8080/api/v1/system/ping
# {"code":0,"message":"success","data":{"service":"openvpp-demo","status":"UP",...}}
```

返回 `"status":"UP"` 即启动成功。下一步建议直接进入
[教程 02：园区需求响应贯穿案例](02-park-demand-response.md)。

## 常见问题

- **全局 Maven 镜像不可达导致依赖解析失败**：始终带 `-s settings-openvpp.xml` 显式指定工程自带 settings（走公共镜像），无需改动全局配置。
- **端口 8080 被占用**：修改 `openvpp-app/src/main/resources/application.yml` 的 `server.port`，或释放端口后重启。
- **JDK 版本不符**：编译阶段即报错。用 `mvn -v` 确认 Maven 绑定的 JDK 为 11 或更高（`release=11` 保证字节码兼容 11）。
- **构建产物位置**：`openvpp-app/target/openvpp-app-1.0.0.jar`，可直接 `java -jar` 启动（教程 02 采用此形态）。

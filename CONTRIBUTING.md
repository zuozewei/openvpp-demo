# 贡献指南

感谢关注 openvpp-demo！这是一个专栏配套的教学示例工程，欢迎通过 Issue 反馈问题、
通过 PR 改进代码与文档。

## 环境要求

- JDK 11（编译目标锁定 `release=11`，拒绝 Java 12+ API）
- Maven 3.6+
- （可选）Docker Compose v2：运行教程 03 的集成回归

## 本地开发

```bash
# 构建与全量测试
mvn -s settings-openvpp.xml test

# 打包并启动单体形态
mvn -s settings-openvpp.xml -pl openvpp-app -am -DskipTests package
java -jar openvpp-app/target/openvpp-app-1.0.0.jar
```

实操入门见 [docs/tutorials/](docs/tutorials/README.md)。

## 提交规范

提交信息使用「类型: 中文简述」格式：

| 类型 | 用途 |
|------|------|
| 新增 | 新功能/新演示 |
| 修复 | 缺陷修复 |
| 文档 | 注释、教程、口径修订（不改行为） |
| 重构 | 行为不变的结构调整 |
| 测试 | 补充测试 |

## PR 流程

1. Fork / 拉分支（`feat/`、`fix/`、`docs/` 前缀）；
2. 保证 `mvn -s settings-openvpp.xml test` 全绿（live 用例默认跳过属正常）；
3. 提交 PR 并说明改动动机与验证方式。

## 代码与文档风格

- 注释与文档使用中文，与现有风格保持一致；
- 教程文档放 `docs/tutorials/`，统一「目标 → 前置条件 → 操作步骤 → 验证方法 → 常见问题」结构；
- **内容红线（必须遵守）**：示例数据一律虚构，示例命名不指向真实项目、公司或客户；
  不提交任何环境地址、内网 IP 或凭据——自备环境的连接信息一律经系统属性/环境变量注入。

## 安全相关问题

不要通过公开 Issue 提交安全漏洞，见 [SECURITY.md](SECURITY.md)。

## 许可证

提交即表示同意以 [Apache-2.0](LICENSE) 许可证发布你的贡献。

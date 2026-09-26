# 更新日志

本项目的所有重要变更记录于此。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循语义化版本。

## [Unreleased]

### 文档

- 文档体系开源化重构：新增 `docs/` 文档中心（教程 `docs/tutorials/` 01-07、
  核算底稿 `docs/case-study/`、快照对照 `docs/snapshots.md`）；
- README 重写为开源门面结构，专栏配套教程导航置于醒目位置；
- 补齐开源配套：Apache-2.0 LICENSE、CONTRIBUTING、SECURITY、CODE_OF_CONDUCT、
  Issue/PR 模板。

### 工程调整

- TSDB live 基准的连接信息改为系统属性注入（用法见教程 06）；

- RAG 演示语料目录改为命令行参数/环境变量注入，仓库自带示例语料 `tools/ai/corpus/`；
- 区域结算示例类更名为 `RegionAFreqRanking`、
  `RegionBPeakSettlement`（虚构示例口径，行为不变）；
- 台账与光伏算例测试数据改用虚构区域标签与示例纬度。

## [1.0.0] - 2026-09

### 新增

- 虚拟电厂（VPP）示例工程首次发布：11 个 Maven 模块覆盖
  接入 → 评估 → 聚合 → 调度 → 结算完整链路；
- 园区需求响应贯穿案例（第 19 篇口径）：三路径、结算四量、争议更正版本化；
- Docker Compose 一键交付（第 25 篇）：应用 + MySQL + Redis + EMQX；
- 算法番外配套：MPC 调度、区域结算（第 33-35 篇）、GBDT 训推链（第 47 篇）、
  多协议接入地图（第 48 篇）；
- 零依赖 Python 工具集：GBDT 副车、INT8 量化、RAG 检索；
- 工程口径：事务内原子认领、Redis 幂等守卫（故障退化 + 提交后缓存写入）、
  H2/MySQL 方言自适应、真实环境回归 MySqlComposeIT。

[Unreleased]: https://gitee.com/zuozewei/openvpp-demo/compare/master...HEAD

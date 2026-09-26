# 教程 05：AI 工具集（GBDT / 量化 / RAG）

> 目标：运行 `tools/ai/` 下三个**零第三方依赖**（纯 Python 标准库）的算法演示，
> 并理解它们与 Java 主工程的联动关系。

## 前置条件

- Python 3.8+（无 pip 依赖）。

## 脚本一：GBDT 训推链演示（`gbdt_demo.py`，第 47 篇）

复现"物理基线 + GBDT 学残差 + 副车推理服务"的完整链路：

1. 物理侧：太阳几何 / 组件温度 / 温度降额（与 Java 侧 `PvPhysics` 逐系数一致，公式是两个语言间的"契约"）；
2. 训练侧：微型梯度提升树（决策桩 + 平方误差）只学残差，带质量闸门（样本 < 200 拒训）；
3. 推理侧：`http.server` 副车（默认 `127.0.0.1:43317`），`GET /health`、`POST /predict`。

```bash
# 本地自检（不启服务）：对比"仅物理基线"与"物理+残差模型"的 MAE，并校验特征键序契约
python3 tools/ai/gbdt_demo.py --selfcheck

# 启动推理副车，配合 Java 侧 GbdtClient 联调（live 用例，无法连通时自动跳过）
python3 tools/ai/gbdt_demo.py
```

跨语言契约：特征键序（`FEATURE_NAMES`）训练与推理必须同一顺序，靠纪律保证——
这是专栏第 47 篇的核心论点：跨语言训推链最脆弱的不是算法，是契约管理。

## 脚本二：INT8 对称量化演示（`quantize_demo.py`，第 29 篇）

复现"训练机 FP32 → 边缘 INT8"的数值实验：对称量化（scale = max|w|/127）、
反量化前向误差与余弦相似度、模型体积 4:1 对比。

```bash
python3 tools/ai/quantize_demo.py
```

预期输出包含量化前后输出误差、余弦相似度与体积对比，误差量级足以说明
"边缘侧部署用 INT8 换 4 倍体积与带宽"的教学结论。

## 脚本三：RAG 国标检索演示（`rag_demo.py`，第 30 篇）

复现 RAG 的"检索"半链路：`##` 标题分块 → 中文 bigram + TF-IDF 向量化 → 余弦相似度 Top-K。

```bash
# 默认使用仓库自带示例语料 tools/ai/corpus/
python3 tools/ai/rag_demo.py "虚拟电厂调节容量不低于多少"

# 指定你自己的 Markdown 语料目录（可重复传入多个）
python3 tools/ai/rag_demo.py "查询问题" --corpus <Markdown目录>
# 或环境变量（逗号分隔多个目录）
RAG_CORPUS_DIRS="目录1,目录2" python3 tools/ai/rag_demo.py "查询问题"
```

把语料换成你自己的国标解析笔记，检索效果即刻"非玩具"——生产系统将 TF-IDF
替换为 embedding 模型即可，分块与检索链路结构不变。

## 与 Java 主工程的关系

| 演示 | 联动模块 | 关系 |
|------|----------|------|
| GBDT 副车 | `openvpp-assessment` 的 `com.openvpp.assessment.predict`（`GbdtClient`） | Java 主战、Python 副车：GBDT 模型在 Python 侧训练/推理，Java 经 HTTP 调用，公式与特征键序为跨语言契约 |
| INT8 量化 | 边缘侧部署叙事 | 独立数值实验，对应 `openvpp-edge` 的教学场景 |
| RAG 检索 | 无直接代码联动 | 独立教学演示，语料口径与 `openvpp-aggregator` 准入门槛教学口径一致 |

## 常见问题

- **gbdt_demo --selfcheck 失败**：确认使用 Python 3.8+；自检同时校验跨语言公式契约，若改动过
  `PvPhysics` 或 `FEATURE_NAMES` 需两侧同步。
- **43317 端口被占用**：修改脚本内 `GBDT_PORT` 后同步修改 Java 侧联调用例的系统属性。
- **rag_demo 未找到语料**：确认目录存在且含 `.md` 文件；默认语料在 `tools/ai/corpus/`。

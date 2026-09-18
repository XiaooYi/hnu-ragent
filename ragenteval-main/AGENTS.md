# 比特严选 RAG 客服助手 - Codex 协作指南

## 🎯 项目一句话定位
为虚拟电商「比特严选」构建一个生产级 RAG 客服系统，
覆盖售前选购 / 售中咨询 / 售后服务 / 故障诊断 4 大场景。

仓库角色：**评测项目侧**（`ragenteval`），负责评估集、知识库、评测脚本、报告产出。
被评系统在 `ragent` 仓库，本项目不包含 Java 后端代码。

## 📐 核心架构决策

### 决策 1：RAG + Tool Calling 混合架构
- **静态描述类内容** → RAG 检索（商品详情、政策、手册）
- **动态/组合/计算类** → Tool Calling（对比、推荐、库存、价格）

### 决策 2：不维护商品对比文档
- 理由：组合爆炸（50 商品 → 1225 对比文档）
- 替代方案：`compare_products` 工具动态对比

### 决策 3：意图体系 = 3 个一级意图 + 22 个二级意图
- 一级：SUPPORT（17）/ FEEDBACK（3）/ CHAT（2）
- 详见 `docs/「比特严选」用户意图体系设计.md`

## 🗺️ 项目地图

| 我想了解... | 看这里 |
|---|---|
| 评测架构全景 | `docs/eval-current-state.md` |
| 意图体系 | `docs/「比特严选」用户意图体系设计.md` |
| 评估集设计 | `docs/「比特严选」评估集 Query 模板设计.md` + `eval/rag/dataset/` |
| 文档体系 | `docs/「比特严选」130 文档清单反推设计.md` + `knowledge_base/` |
| 评测规划 | `docs/evaluation-plan.md` |
| 评测代码与指标 | `eval/`（common / rag / pipeline / metrics / report） |

## 🚧 当前进度

- [x] 业务场景设计
- [x] 意图体系设计
- [x] 评估集规划（150 条）
- [x] 文档体系规划（115 篇）
- [x] 商品文档生产管线（知识库 115/115 篇）
- [x] 评估集落地（eval_set_v1.jsonl 150 条）
- [x] 评测脚本体系（CLI / pipeline / metrics / report 全链路已跑通）
- [x] 自建指标（意图 / Hit@K / Recall@K / MRR / 误拒率 / TTFT）
- [x] RAGAS 指标（faithfulness / answer_relevancy / answer_correctness / context_precision / context_recall）
- [x] 报告产出（markdown / per_sample.csv / failures.jsonl / slides.html）
- [ ] Tool Calling 评测
- [ ] 端到端评估闭环

说明：评测体系的录制→评分→报告流水线已完善，自建指标和 RAGAS 指标均可通过 `python -m eval rag all` 一键运行。

## 🛠️ 技术栈

- **语言**：Python 3.11（RAGAS 依赖要求）
- **LLM**：DeepSeek / Qwen / GPT-4
- **向量库**：Chroma / Milvus
- **Embedding**：bge-m3 / text-embedding-3
- **评测框架**：RAGAS 0.2+
- **Judge 模型**：gpt-5.4-mini（aihubmix）
- **框架**：不依赖 LangChain（手写实现以求可控）

## 📋 协作规范

### 写代码前必做
1. 阅读 `AGENTS.md`（本文件）
2. 阅读根目录 `README.md` 了解评测体系
3. 阅读 `eval/common/schemas.py` 了解核心数据模型

### 代码风格
- 类型注解必须有
- docstring 用 Google 风格
- 关键函数必须配单测
- 命名遵循 PEP8

### Git 提交
- 用语义化 commit：`feat:` / `fix:` / `docs:` / `refactor:`
- 一次提交只做一件事

## ⚠️ 重要约束

- **不引入 LangChain/LlamaIndex 等重框架**：保持代码可控可读
- **优先用 Markdown 而非代码生成文档**：LLM 友好
- **评估驱动**：每个核心模块必须有评估集
- **可追溯性**：所有 prompt 单独文件管理，方便迭代
- **录制与评分分离**：跑一次 runner 落 runs/*.jsonl，后续可反复评分不重复调接口

## 💡 Codex 工作模式建议

- 修改代码前先 read 相关文件
- 大改动前先列 plan
- 涉及多文件变更时分步提交
- 不确定时优先问而不是猜

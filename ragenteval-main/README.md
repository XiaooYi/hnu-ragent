# 比特严选 RAG 客服助手 — 评测项目

为虚拟电商「比特严选」构建生产级 RAG 客服系统，覆盖售前选购 / 售中咨询 / 售后服务 / 故障诊断 4 大场景。

本项目是**评测侧**仓库，负责评估集维护、知识库管理、评测脚本和报告产出。
被评系统在 `ragent` 仓库（Java），本项目为纯 Python 工具链。

整套评测围绕三个数据类型：`EvalSample`（输入）/ `EvalRecord`（录制结果）/ `MetricResult`（指标输出）。**先读 `eval/common/schemas.py`，再读脚本。**

## 快速开始

### 环境变量

```bash
export RAGENT_BASE_URL=http://localhost:9090/api/ragent
export RAGENT_USERNAME=admin                          # 可省，Ragent 默认用户名/密码
export RAGENT_PASSWORD=admin                          # 可省
export AIHUBMIX_API_KEY=<your_aihubmix_key>           # RAGAS judge 用
export JUDGE_MODEL=gpt-5.4-mini                       # 可省，RAGAS judge 默认值
```

ragent 服务端需开启评测旁路：`app.eval.enabled: true`。Python 3.11（RAGAS 依赖要求）。

> 如果 `python -m eval` 报 `No module named eval`，改用 `python eval/common/cli.py ...`。

### 一键全量

```bash
python -m eval rag all                      # run → score → report 一条龙
python -m eval rag all --limit 5            # smoke：只跑 5 条
python -m eval rag all -w 5                 # 5 线程并行
python -m eval rag all --skip-ragas         # 跳过 LLM-judge，仅自建指标
python -m eval rag all --ragas-limit 10     # RAGAS 只评前 10 条
python -m eval rag all --ragas-n 3          # RAGAS 独立跑 3 次取均值
```

### 分步运行

```bash
# 1. 调 ragent 跑评测
python -m eval rag run --limit 20                        # 全量 20 条
python -m eval rag run --limit 5                         # smoke
python -m eval rag run --filter-intent S1_选购推荐        # 定向调试
python -m eval rag run --debug                           # 保留原始 SSE 字节流

# 2. 算所有指标
python -m eval rag score                                 # 自建 + RAGAS
python -m eval rag score --skip-ragas                    # 跳过 LLM-judge
python -m eval rag score --ragas-limit 10                # RAGAS 只评前 10 条
python -m eval rag score --ragas-n 3                     # RAGAS 3 次取均值
python -m eval rag score runs/v1_xxx.jsonl               # 指定 runs 文件

# 3. 出报告
python -m eval rag report                                # 默认 swiss 主题
python -m eval rag report --theme magazine               # 杂志风
python -m eval rag report --only-slides                  # 只重出 slides.html

# 4. A/B 对比
python -m eval rag diff v1_xxx v1_yyy                    # 终端看对比
python -m eval rag diff run_a run_b -o reports/diff.md   # 同时落 markdown
```

---

## 项目结构

```
ragenteval/
├── README.md                    你正在读的文件
├── CLAUDE.md                    协作指南（进度、架构决策、规范）
│
├── eval/                        评测工具链（Python）
│   ├── __main__.py              让 python -m eval 可用
│   ├── common/                  共享基础设施
│   │   ├── schemas.py           ⭐ 核心数据模型（EvalSample / EvalRecord / MetricResult）
│   │   └── cli.py               唯一 CLI 入口（rag → run / score / report / all）
│   ├── rag/                     RAG 评测
│   │   ├── dataset/             评估集输入
│   │   │   ├── eval_set_v1.jsonl    20 条评估集（默认 --limit）
│   │   │   ├── eval_set_v1_all.jsonl 150 条全量
│   │   │   └── doc_id_map.json      本地生成：业务码 ↔ ragent 内部 ID 映射（不进 git）
│   │   ├── pipeline/            主流程
│   │   │   ├── runner.py        调 ragent SSE + 评测旁路
│   │   │   └── score.py         编排所有指标计算
│   │   ├── metrics/             指标模块
│   │   │   ├── intent.py        意图 Top-1 准确率
│   │   │   ├── retrieval.py     Hit@K / Recall@K / MRR
│   │   │   ├── behavior.py      误拒率 / 过召回率
│   │   │   ├── latency.py       TTFT P50/P95/P99
│   │   │   ├── ragas_judge.py   5 个 RAGAS LLM-as-judge
│   │   │   └── _common.py       共享工具
│   │   ├── report/              报告产出
│   │   │   ├── markdown.py      出 report.md + per_sample.csv + failures.jsonl
│   │   │   └── slides.py        出 16:9 HTML PPT
│   │   ├── templates/           HTML 模板（swiss / magazine）
│   │   └── init/                一次性初始化脚本
│   │       ├── create_kbs.py    建 4 个知识库
│   │       ├── upload_docs.py    灌 115 篇文档（断点续传）
│   │       ├── build_intent_tree.py  灌意图树
│   │       ├── reset_kbs.py      清空 KB/文档（默认 dry-run）
│   │       └── reset_intent_tree.py  清空意图树（默认 dry-run）
│   ├── agent/                   Agent 评测（预留）
│   │   ├── dataset/
│   │   ├── pipeline/
│   │   ├── metrics/
│   │   └── report/
│   ├── runs/                    录制产物（v1_<ts>.jsonl）
│   └── reports/                 报告产物（v1_<ts>/）
│
├── examples/                    示例产出（面试展示 / 新手上手）
│   ├── runs/v1_example.jsonl
│   └── reports/v1_example/
│
├── knowledge_base/              115 篇 Markdown 文档
│   ├── 01_product/              商品详情 50 + 选购指南 15 = 65 篇
│   ├── 02_manual/               使用手册 15 + APP 5 + 配网 5 = 25 篇
│   ├── 03_policy/               售后政策 15 篇
│   ├── 04_faq/                  故障排查 10 篇
│   └── _meta/                   文档索引、模板、映射表
│
└── docs/                        设计文档
    ├── eval-current-state.md     评测架构全景（对外 review）
    ├── evaluation-plan.md        评测规划
    ├── 「比特严选」用户意图体系设计.md
    ├── 「比特严选」评估集 Query 模板设计.md
    └── 「比特严选」130 文档清单反推设计.md
```

> `eval/rag/dataset/doc_id_map.json` 是初始化时本地生成的，保存 ragent 数据库内部文档 ID。不同机器/数据库/重新初始化后 ID 会变，不应提交；换环境后重新执行 `eval/rag/init/upload_docs.py` 生成。

---

## 评测流程

```
eval_set_v1.jsonl (150 条)
        │  python -m eval rag run      ← 调 ragent /rag/v3/chat (SSE) + /rag/eval (JSON)
        ▼
   runs/v1_<ts>.jsonl                  ← 录制结果（EvalRecord × N）
        │  python -m eval rag score    ← 自建指标 + RAGAS LLM-as-judge
        ▼
   reports/<run>/_scores.json           ← 中间产物
        │  python -m eval rag report   ← 呈现
        ▼
   reports/<run>/{report.md, per_sample.csv, failures.jsonl, slides.html}
```

核心设计：**录制与评分分离**——跑一次 runner，后续可反复评分不重复调接口。

---

## 指标看板

| 维度 | 指标 | 来源 | 参考目标   |
|---|---|---|--------|
| 意图 | Top-1 准确率 | `rag/metrics/intent.py` | ≥ 92%  |
| 检索 | Doc Hit@5 | `rag/metrics/retrieval.py` | ≥ 90%  |
| 检索 | Recall@5 (must) | `rag/metrics/retrieval.py` | —      |
| 检索 | context_recall | `rag/metrics/ragas_judge.py` | ≥ 0.80 |
| 检索 | context_precision | `rag/metrics/ragas_judge.py` | ≥ 0.75 |
| 生成 | faithfulness | `rag/metrics/ragas_judge.py` | ≥ 0.90 |
| 生成 | answer_correctness | `rag/metrics/ragas_judge.py` | ≥ 0.80 |
| 生成 | answer_relevancy | `rag/metrics/ragas_judge.py` | ≥ 0.85 |
| 行为 | 误拒率 / 过召回率 | `rag/metrics/behavior.py` | ≤ 3%   |
| 性能 | 首字 P95 (TTFT) | `rag/metrics/latency.py` | ≤ 10s  |

> **P95 看首字、不看整流。** 对话产品的体感卡点是答案首个 token 到达。
> **不评 Tool Calling**：评估集未带 `tool_calls_gold`。
> **`ground_truth` 字段质量提醒**：评估集大部分 `ground_truth` 仍是元指令格式（"应推荐..."/"应命中..."），这会让 RAGAS `answer_correctness` 偏低。

---

## 人工复核

`per_sample.csv` 会在每个 RAGAS 指标后面自动补一个 `*_manual` 空列。
人工复核时直接填这些列（0-1 或 0-100 均可）；再次运行 `python -m eval rag report`
会按 **人工列优先，空值回退 RAGAS** 的口径重算 `report.md`、`failures.jsonl` 和 `slides.html`。

---

## 报告主题

报告默认 **瑞士国际主义风**（高级灰白 + 近黑 + 克莱因蓝 IKB、Inter 无衬线）。
横向翻页：键盘 ← → / 滚轮 / 触屏；按 `ESC` 看索引、按 `B` 切静态。打印输出 16:9 PPT。

```bash
python -m eval rag report                       # 默认 swiss
python -m eval rag report --theme magazine      # 电子杂志风
```

模板源文件：`eval/rag/templates/swiss_template.html` / `magazine_template.html`。

---

## 进一步阅读

| 我要... | 看这里 |
|---|---|
| 了解架构和进度 | `CLAUDE.md` |
| 了解评测设计全景 | `docs/eval-current-state.md` |
| 了解评估集设计 | `docs/「比特严选」评估集 Query 模板设计.md` |
| 了解知识库设计 | `docs/「比特严选」130 文档清单反推设计.md` |

---

## 技术栈

Python 3.11 · RAGAS 0.2+ · gpt-5.4-mini (judge) · 16:9 HTML 报告 · 不依赖 LangChain/LlamaIndex

## 协作规范

- 修改代码前先读 `CLAUDE.md` 和 `eval/common/schemas.py`
- 类型注解、Google 风格 docstring、PEP8
- 语义化 commit：`feat:` / `fix:` / `docs:` / `refactor:`

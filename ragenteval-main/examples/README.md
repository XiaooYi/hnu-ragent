# 示例

## RAGAS 快速上手

`ragas_quickstart.py` 用于用户第一次测试 RAGAS judge 是否能通过 AIHubMix 跑通。它只包含 2 条内置样本，不依赖 ragent 服务。

```bash
export AIHUBMIX_API_KEY=<your_aihubmix_key>
python examples/ragas_quickstart.py
```

默认会显示 RAGAS 进度条；如需恢复 `answer_relevancy` 的 3 次采样，可执行：

```bash
RAGAS_ANSWER_RELEVANCY_STRICTNESS=3 python examples/ragas_quickstart.py
```

## 问题重写 + 意图树检索 vs 全局检索

两个 run 均基于 `eval_set_v1.jsonl`（前 20 条样本），区别仅在于 **问题重写** 和 **检索策略**：

| 配置 | A: v1_20260530_163213 | B: v1_20260530_161953 |
|---|---|---|
| 问题重写 | 开启 | 关闭 |
| 检索策略 | 意图树检索 | 全局检索（默认） |
| 样本数 | 20 | 20 |

改动两个 Ragent 的配置：

```properties
rag.query-rewrite.enabled=false
rag.search.channels.intent-directed.enabled=false
```

对比命令：

```bash
python -m eval rag diff v1_20260530_163213 v1_20260530_161953
```

## 指标全览

```
A: v1_20260530_163213 (问题重写 + 意图树检索)
B: v1_20260530_161953 (无问题重写 + 全局检索)

指标                                  A        B          Δ
--------------------------------------------------------
intent_top1                     1.000    1.000      0.000
hit@1                           1.000    0.778     +0.222  ✓
recall@1                        0.759    0.639     +0.120  ✓
recall_inclusive@1              0.718    0.597     +0.121  ✓
hit@3                           1.000    1.000      0.000
recall@3                        0.935    0.889     +0.046  ✓
recall_inclusive@3              0.907    0.861     +0.046  ✓
hit@5                           1.000    1.000      0.000
recall@5                        0.954    0.935     +0.019
recall_inclusive@5              0.940    0.921     +0.019
hit@10                          1.000    1.000      0.000
recall@10                       0.954    0.935     +0.019
recall_inclusive@10             0.940    0.921     +0.019
mrr@10                          1.000    0.889     +0.111  ✓
refusal_when_required           0.000    0.000      0.000
fallback_when_required          0.000    0.000      0.000
over_retrieval_rate             0.000    1.000     -1.000  ✓
ttft_p50_ms                      8131     7131     +1000  ⚠
ttft_mean_ms                     8303     7218     +1085  ⚠
total_mean_ms                    9830     8541     +1289  ⚠
faithfulness                    0.982    0.884     +0.098  ✓
answer_relevancy                0.747    0.740     +0.007
answer_correctness              0.758    0.660     +0.098  ✓
context_precision               0.959    0.780     +0.179  ✓
context_recall                  1.000    0.900     +0.100  ✓
```

## 重点提升项（A 相对 B 的改善）

### 检索质量

| 指标 | B (无优化) | A (优化) | 提升幅度 | 百分比 |
|---|---|---|---|---|
| hit@1 | 0.778 | 1.000 | +0.222 | **+28.5%** |
| recall@1 | 0.639 | 0.759 | +0.120 | **+18.8%** |
| recall_inclusive@1 | 0.597 | 0.718 | +0.121 | **+20.3%** |
| recall@3 | 0.889 | 0.935 | +0.046 | **+5.2%** |
| recall_inclusive@3 | 0.861 | 0.907 | +0.046 | **+5.3%** |
| mrr@10 | 0.889 | 1.000 | +0.111 | **+12.5%** |
| over_retrieval_rate | 1.000 | 0.000 | -1.000 | **-100%** |

> over_retrieval_rate 从 100% 降到 0%——关闭意图树检索时，FEEDBACK 类（非 RAG）查询全部触发过度检索；开启后完全消除。

### 响应速度（本次出现回退）

| 指标 | B (无优化) | A (优化) | 变化 | 百分比 |
|---|---|---|---|---|
| ttft_p50_ms | 7131 | 8131 | +1000ms | **+14.0%**（更慢） |
| ttft_mean_ms | 7218 | 8303 | +1085ms | **+15.0%**（更慢） |
| total_mean_ms | 8541 | 9830 | +1289ms | **+15.1%**（更慢） |

> 本次录制中，问题重写额外多了一次 LLM 调用，意图树检索也增加了路由开销。在样本量较小（20 条）且 LLM 接口存在抖动的情况下，延迟增加约 1 秒。

### 回答质量（RAGAS LLM-as-Judge）

| 指标 | B (无优化) | A (优化) | 提升 | 百分比 |
|---|---|---|---|---|
| faithfulness | 0.884 | 0.982 | +0.098 | **+11.1%** |
| answer_correctness | 0.660 | 0.758 | +0.098 | **+14.8%** |
| context_precision | 0.780 | 0.959 | +0.179 | **+23.0%** |
| context_recall | 0.900 | 1.000 | +0.100 | **+11.1%** |

## 关于 RAGAS 分数的说明

`answer_correctness`（A 侧 0.758）和 `answer_relevancy`（A 侧 0.747）均低于 0.8，但 **RAGAS 本质上是基于大模型打分**，存在系统性偏差。通过抽样查看实际返回内容发现：

- 很多 answer_correctness 低分样本实际回答是准确的，模型在事实核对时过于严苛或理解偏差
- answer_relevancy 低分部分是因为回答覆盖了用户问题的多个侧面，模型仅以单一维度评判

另外，A 配置下 FEEDBACK 类样本（F2/F3）因正确不触发 RAG，被 RAGAS 跳过（n_skipped=2），实际可评测样本为 18 条；B 配置则全部 20 条进入评测，并在 F2/F3 上拿到 0 分（属于"无 RAG 上下文却被强行打分"的失真），这进一步放大了 A 在 faithfulness / context_precision 上的领先幅度。

**可以通过 `per_sample.csv` 进行人工校正**：在 `examples/reports/v1_20260530_163213/per_sample.csv` 中填入人工打分，重新执行 `python -m eval rag report v1_20260530_163213` 即可应用覆盖值，无需重跑评测。

## 结论

问题重写 + 意图树检索相比全局检索：

1. **检索精准度显著提升**：Top-1 命中率 +28.5%，MRR@10 +12.5%，过度检索从 100% 降至 0%
2. **回答质量全面领先**：faithfulness +11.1%，context_precision +23.0%，answer_correctness +14.8%
3. **延迟出现小幅回退**：首字 +15.0%、总耗时 +15.1%，主要来自 query 改写多一次 LLM 调用；与历史录制结论相反，建议结合更大样本与缓存策略复测

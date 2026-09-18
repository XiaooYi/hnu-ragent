"""行为分类：误拒率 / 答案兜底率 / 过召回率。

业务红线类指标，与"问题是否应该走 RAG"对齐：
- 误拒率：requires_rag=true 但召回为空（检索失败）
- 答案兜底率：requires_rag=true 但 response 输出"未检索到与问题相关的文档内容"
- 过召回率：requires_rag=false 但走了 RAG 召回（应走 SYSTEM 兜底话术）
"""
from __future__ import annotations

from eval.rag.metrics._common import slice_mean
from eval.common.schemas import EvalRecord, MetricResult

FALLBACK_MARKER = "未检索到与问题相关的文档内容"


def compute(records: list[EvalRecord]) -> list[MetricResult]:
    return [_refusal_when_required(records), _fallback_when_required(records), _over_retrieval(records)]


def _refusal_when_required(records: list[EvalRecord]) -> MetricResult:
    def value(r: EvalRecord) -> float:
        return 1.0 if len(r.retrieved_doc_ids) == 0 else 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value, lambda r: r.requires_rag)
    return MetricResult("refusal_when_required", overall, by_l1, by_l2, per_sample)


def _fallback_when_required(records: list[EvalRecord]) -> MetricResult:
    def value(r: EvalRecord) -> float:
        return 1.0 if FALLBACK_MARKER in (r.response or "") else 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value, lambda r: r.requires_rag)
    return MetricResult("fallback_when_required", overall, by_l1, by_l2, per_sample)


def _over_retrieval(records: list[EvalRecord]) -> MetricResult:
    def value(r: EvalRecord) -> float:
        return 1.0 if len(r.retrieved_doc_ids) > 0 else 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value, lambda r: not r.requires_rag)
    return MetricResult("over_retrieval_rate", overall, by_l1, by_l2, per_sample)

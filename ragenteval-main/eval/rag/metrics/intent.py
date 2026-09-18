"""意图分类：Top-1 准确率。

意图错 → 后续召回 / 答案大概率都错，最上游的闸门。RAGAS 完全不管分类。
"""
from __future__ import annotations

from eval.rag.metrics._common import slice_mean
from eval.common.schemas import EvalRecord, MetricResult


def compute(records: list[EvalRecord]) -> list[MetricResult]:
    """Top-1: intent_pred == intent_l2 的比例（intent_l2 为空的样本不算）。"""

    def value(r: EvalRecord) -> float | None:
        if not r.intent_l2:
            return None
        return 1.0 if r.intent_pred == r.intent_l2 else 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value)
    return [
        MetricResult(
            name="intent_top1",
            overall=overall,
            by_intent_l1=by_l1,
            by_intent_l2=by_l2,
            per_sample=per_sample,
        )
    ]

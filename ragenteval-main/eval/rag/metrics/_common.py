"""指标共享工具：按 intent_l1 / intent_l2 切片求均值。"""
from __future__ import annotations

from collections import defaultdict
from typing import Callable, Iterable

from eval.common.schemas import EvalRecord


def slice_mean(
    records: Iterable[EvalRecord],
    value_fn: Callable[[EvalRecord], float | None],
    eligible_fn: Callable[[EvalRecord], bool] | None = None,
) -> tuple[float | None, dict[str, float | None], dict[str, float | None], dict[str, float | None]]:
    """按 intent_l1 / intent_l2 / 每条样本切，返回 (overall, by_l1, by_l2, per_sample)。

    - value_fn:    record → 该样本上的指标值（None 表示样本不算分）
    - eligible_fn: 哪些样本纳入统计；None 表示全量
    """
    overall_vals: list[float] = []
    bucket_l1: dict[str, list[float]] = defaultdict(list)
    bucket_l2: dict[str, list[float]] = defaultdict(list)
    per_sample: dict[str, float | None] = {}

    for r in records:
        if eligible_fn is not None and not eligible_fn(r):
            per_sample[r.query_id] = None
            continue
        v = value_fn(r)
        per_sample[r.query_id] = v
        if v is None:
            continue
        overall_vals.append(v)
        bucket_l1[r.intent_l1 or "?"].append(v)
        bucket_l2[r.intent_l2 or "?"].append(v)

    def _mean(xs: list[float]) -> float | None:
        return sum(xs) / len(xs) if xs else None

    return (
        _mean(overall_vals),
        {k: _mean(v) for k, v in bucket_l1.items()},
        {k: _mean(v) for k, v in bucket_l2.items()},
        per_sample,
    )


def is_retrieval_eligible(r: EvalRecord, *, inclusive: bool = False) -> bool:
    """检索指标只统计 requires_rag=true 且评估集标了 reference 的样本。

    inclusive=True 时，nice-only 样本（must 空但 nice 非空）也视为 eligible，
    供 recall_inclusive@K 使用。
    """
    if not r.requires_rag:
        return False
    if inclusive:
        return bool(r.reference_doc_ids) or bool(r.reference_doc_ids_nice)
    return bool(r.reference_doc_ids)

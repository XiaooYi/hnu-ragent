"""检索：Hit@K / Recall@K (must + inclusive) / MRR@10。

样本过滤：仅统计 ``requires_rag=true 且 reference_doc_ids 非空`` 的样本。
SYSTEM 兜底类样本不污染检索指标。
"""
from __future__ import annotations

from eval.rag.metrics._common import is_retrieval_eligible, slice_mean
from eval.common.schemas import EvalRecord, MetricResult

K_VALUES = (1, 3, 5, 10)


def compute(records: list[EvalRecord]) -> list[MetricResult]:
    results: list[MetricResult] = []
    for k in K_VALUES:
        results.append(_hit_at_k(records, k))
        results.append(_recall_at_k(records, k, inclusive=False))
        results.append(_recall_at_k(records, k, inclusive=True))
    results.append(_mrr_at_10(records))
    return results


def _hit_at_k(records: list[EvalRecord], k: int) -> MetricResult:
    def value(r: EvalRecord) -> float:
        topk = set(r.retrieved_doc_ids[:k])
        ref = set(r.reference_doc_ids)
        return 1.0 if topk & ref else 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value, is_retrieval_eligible)
    return MetricResult(f"hit@{k}", overall, by_l1, by_l2, per_sample)


def _recall_at_k(records: list[EvalRecord], k: int, *, inclusive: bool) -> MetricResult:
    def value(r: EvalRecord) -> float:
        topk = set(r.retrieved_doc_ids[:k])
        ref = set(r.reference_doc_ids)
        if inclusive:
            ref = ref | set(r.reference_doc_ids_nice)
        return len(topk & ref) / len(ref) if ref else 0.0

    name = f"recall_inclusive@{k}" if inclusive else f"recall@{k}"
    eligible = (lambda r: is_retrieval_eligible(r, inclusive=True)) if inclusive else is_retrieval_eligible
    overall, by_l1, by_l2, per_sample = slice_mean(records, value, eligible)
    return MetricResult(name, overall, by_l1, by_l2, per_sample)


def _mrr_at_10(records: list[EvalRecord]) -> MetricResult:
    def value(r: EvalRecord) -> float:
        ref = set(r.reference_doc_ids)
        for rank, doc in enumerate(r.retrieved_doc_ids[:10], start=1):
            if doc in ref:
                return 1.0 / rank
        return 0.0

    overall, by_l1, by_l2, per_sample = slice_mean(records, value, is_retrieval_eligible)
    return MetricResult("mrr@10", overall, by_l1, by_l2, per_sample)

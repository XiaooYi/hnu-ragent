"""性能：首字延迟 (TTFT) 的 P50/均值 + 整流均值。

对话产品的体感卡点是 **正式回答首个 token 到达**（type=response 的首个 delta），
而不是完整流的总耗时（总耗时随 token 数线性增长，不反映"卡顿"）。
小样本下 P95/P99 退化为极值，改用均值。
"""
from __future__ import annotations

import statistics

from eval.common.schemas import EvalRecord, MetricResult


def compute(records: list[EvalRecord]) -> list[MetricResult]:
    ttfts = sorted(_first_token_or_total(r) for r in records if _first_token_or_total(r))
    totals = sorted(r.latency_ms for r in records if r.latency_ms)

    def pct(xs: list[int], q: float) -> float | None:
        return float(xs[min(len(xs) - 1, int(len(xs) * q))]) if xs else None

    ttft_p50 = pct(ttfts, 0.50)
    ttft_mean = float(statistics.mean(ttfts)) if ttfts else None
    total_mean = float(statistics.mean(totals)) if totals else None

    per_sample_ttft = {r.query_id: float(_first_token_or_total(r) or 0) or None for r in records}
    return [
        MetricResult("ttft_p50_ms", ttft_p50, is_pct=False),
        MetricResult("ttft_mean_ms", ttft_mean, is_pct=False, per_sample=per_sample_ttft),
        MetricResult("total_mean_ms", total_mean, is_pct=False),
    ]


def _first_token_or_total(r: EvalRecord) -> int | None:
    """老 runs 没采 first_token_ms 时回退到 latency_ms。"""
    return r.first_token_ms or r.latency_ms or None

"""A/B 指标对比：两份 _scores.json 逐项 diff，终端表格 + markdown 输出。"""
from __future__ import annotations

import json
from pathlib import Path

from eval.common.schemas import MetricResult

LOWER_IS_BETTER = {
    "refusal_when_required",
    "fallback_when_required",
    "over_retrieval_rate",
    "ttft_p50_ms",
    "ttft_p95_ms",
    "ttft_p99_ms",
    "ttft_mean_ms",
    "total_p95_ms",
}

REGRESSION_THRESHOLD = {
    # 比率类：绝对值阈值
    "intent_top1": 0.02,
    "hit@5": 0.03,
    "recall@5": 0.05,
    "mrr@10": 0.05,
    "faithfulness": 0.05,
    "answer_correctness": 0.05,
    "context_precision": 0.05,
    "context_recall": 0.05,
    "answer_relevancy": 0.05,
    # 行为类
    "refusal_when_required": 0.02,
    "over_retrieval_rate": 0.02,
    # 延迟类：绝对值阈值 (ms)
    "ttft_p50_ms": 500,
    "ttft_p95_ms": 500,
    "ttft_p99_ms": 500,
    "ttft_mean_ms": 500,
    "total_p95_ms": 1000,
}

_DEFAULT_THRESHOLD = 0.02


def _is_regression(name: str, delta: float) -> bool:
    threshold = REGRESSION_THRESHOLD.get(name, _DEFAULT_THRESHOLD)
    if name in LOWER_IS_BETTER:
        return delta > threshold
    return delta < -threshold


def _fmt(name: str, v: float | None) -> str:
    if v is None:
        return "—"
    if name.endswith("_ms"):
        return str(int(v))
    return f"{v:.3f}"


def _delta_str(delta: float | None, name: str) -> str:
    if delta is None:
        return "—"
    sign = "+" if delta > 0 else ""
    if name.endswith("_ms"):
        return f"{sign}{int(delta)}"
    return f"{sign}{delta:.3f}"


def render_terminal(
    metrics_a: list[MetricResult],
    metrics_b: list[MetricResult],
    label_a: str,
    label_b: str,
) -> str:
    idx_a = {m.name: m for m in metrics_a}
    idx_b = {m.name: m for m in metrics_b}
    all_names = list(dict.fromkeys([m.name for m in metrics_a] + [m.name for m in metrics_b]))
    regressions: list[str] = []

    lines = [
        f" A: {label_a}",
        f" B: {label_b}",
        "",
        f"{'指标':<28s} {'A':>8s} {'B':>8s} {'Δ':>10s}",
        "-" * 56,
    ]

    for name in all_names:
        a = idx_a.get(name)
        b = idx_b.get(name)
        va, vb = a.overall if a else None, b.overall if b else None
        if va is None and vb is None:
            continue
        delta = (vb - va) if (va is not None and vb is not None) else None
        row = f"{name:<28s} {_fmt(name, va):>8s} {_fmt(name, vb):>8s} {_delta_str(delta, name):>10s}"
        if delta is not None and _is_regression(name, delta):
            row += "  ⚠"
            direction = "↑" if name in LOWER_IS_BETTER else "↓"
            regressions.append(f"  {name}: {_fmt(name, va)} → {_fmt(name, vb)} ({_delta_str(delta, name)} {direction})")
        lines.append(row)

    if regressions:
        lines.append("")
        lines.append("⚠ 退化项：")
        lines.extend(regressions)
    else:
        lines.append("")
        lines.append("✅ 无显著退化")

    return "\n".join(lines)


def render_markdown(
    metrics_a: list[MetricResult],
    metrics_b: list[MetricResult],
    label_a: str,
    label_b: str,
) -> str:
    idx_a = {m.name: m for m in metrics_a}
    idx_b = {m.name: m for m in metrics_b}
    all_names = list(dict.fromkeys([m.name for m in metrics_a] + [m.name for m in metrics_b]))

    lines = [
        f"# A/B 指标对比",
        f"",
        f"| 指标 | {label_a} | {label_b} | Δ |",
        f"|---|---|---|---|",
    ]

    for name in all_names:
        a = idx_a.get(name)
        b = idx_b.get(name)
        va, vb = a.overall if a else None, b.overall if b else None
        if va is None and vb is None:
            continue
        delta = (vb - va) if (va is not None and vb is not None) else None
        ds = _delta_str(delta, name)
        flag = " ⚠" if (delta is not None and _is_regression(name, delta)) else ""
        lines.append(f"| {name} | {_fmt(name, va)} | {_fmt(name, vb)} | {ds}{flag} |")

    # intent_l2 breakdown for core metrics
    core_metrics = ["hit@5", "recall@5", "mrr@10", "faithfulness", "answer_correctness"]
    for metric_name in core_metrics:
        a = idx_a.get(metric_name)
        b = idx_b.get(metric_name)
        if not a or not b:
            continue
        intents = sorted(set(a.by_intent_l2.keys()) | set(b.by_intent_l2.keys()))
        if not intents:
            continue
        lines.append(f"")
        lines.append(f"### {metric_name} 按意图")
        lines.append(f"")
        lines.append(f"| intent | {label_a} | {label_b} | Δ |")
        lines.append(f"|---|---|---|---|")
        for intent in intents:
            va = a.by_intent_l2.get(intent)
            vb = b.by_intent_l2.get(intent)
            delta = (vb - va) if (va is not None and vb is not None) else None
            ds = _delta_str(delta, metric_name)
            lines.append(f"| {intent} | {_fmt(metric_name, va)} | {_fmt(metric_name, vb)} | {ds} |")

    return "\n".join(lines) + "\n"


def compare(run_a: str, run_b: str, *, out_md: Path | None = None) -> str:
    """比较两次 run 的指标。返回终端输出。指定 out_md 时同时写 markdown。

    run_a / run_b 可以是 runs 文件名（如 ``v1_20260524_144551``），
    也可以是完整的 _scores.json 路径。
    """
    from eval.rag.pipeline.score import REPORTS_DIR

    def resolve(label: str) -> tuple[Path, str]:
        p = Path(label)
        if not p.exists():
            p = REPORTS_DIR / label / "_scores.json"
        if not p.exists():
            raise FileNotFoundError(f"找不到 {label}")
        return p, p.parent.name

    path_a, name_a = resolve(run_a)
    path_b, name_b = resolve(run_b)

    payload_a = json.loads(path_a.read_text(encoding="utf-8"))
    payload_b = json.loads(path_b.read_text(encoding="utf-8"))

    metrics_a = [MetricResult(**m) for m in payload_a["metrics"]]
    metrics_b = [MetricResult(**m) for m in payload_b["metrics"]]

    term = render_terminal(metrics_a, metrics_b, name_a, name_b)

    if out_md:
        md = render_markdown(metrics_a, metrics_b, name_a, name_b)
        out_md.write_text(md, encoding="utf-8")

    return term

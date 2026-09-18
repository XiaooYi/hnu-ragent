"""算所有指标：调 metrics/*.py 的 compute()，返回 List[MetricResult]。

入口：
    score(runs_file, skip_ragas=False, ragas_limit=None, ragas_n=1)
        -> tuple[Path, list[MetricResult]]

落盘：把 List[MetricResult] 序列化到 ``reports/<runs_basename>/_scores.json``，
report 阶段直接读它，无需重算。
"""
from __future__ import annotations

import dataclasses
import json
import re
import sys
from collections import Counter
from pathlib import Path

from eval.rag.metrics import behavior, intent, latency, retrieval
from eval.common.schemas import EvalRecord, MetricResult

PROJECT_ROOT = Path(__file__).resolve().parents[3]
RUNS_DIR = PROJECT_ROOT / "eval" / "runs"
REPORTS_DIR = PROJECT_ROOT / "eval" / "reports"

_FRONTMATTER_DOC_ID = re.compile(r"doc_id:\s*(\S+)")


def load_records(runs_file: Path) -> list[EvalRecord]:
    records: list[EvalRecord] = []
    with runs_file.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(EvalRecord.from_dict(json.loads(line)))
    return records


def latest_runs_file() -> Path | None:
    candidates = sorted(RUNS_DIR.glob("v1_*.jsonl"))
    return candidates[-1] if candidates else None


def sanity_check_doc_id_alignment(records: list[EvalRecord]) -> list[dict]:
    """ragent 给的 chunk 维度 docId 应与 contexts frontmatter 还原值一致。

    不一致时 Hit@1/MRR 会失真——尽早发现。返回每条样本的告警列表（空 = 通过）。
    """
    warnings: list[dict] = []
    for r in records:
        ragent_ctx_ids = r.retrieved_context_doc_ids or []
        fm_ctx_ids: list[str | None] = []
        for c in r.retrieved_contexts or []:
            m = _FRONTMATTER_DOC_ID.search(c or "")
            fm_ctx_ids.append(m.group(1) if m else None)
        if not ragent_ctx_ids or not fm_ctx_ids:
            continue
        mismatches = []
        for i in range(min(len(ragent_ctx_ids), len(fm_ctx_ids))):
            fm_doc = fm_ctx_ids[i]
            if fm_doc is None:
                continue
            if ragent_ctx_ids[i] != fm_doc:
                mismatches.append((i, ragent_ctx_ids[i], fm_doc))
        if mismatches:
            warnings.append(
                {
                    "query_id": r.query_id,
                    "retrieved_context_doc_ids": ragent_ctx_ids,
                    "frontmatter_doc_ids": fm_ctx_ids,
                    "mismatches": mismatches,
                }
            )
    return warnings


def status_distribution(records: list[EvalRecord]) -> dict[str, int]:
    return dict(Counter(r.final_status or "unknown" for r in records))


def score(
    runs_file: Path | None = None,
    *,
    skip_ragas: bool = False,
    ragas_limit: int | None = None,
    ragas_n: int = 1,
) -> tuple[Path, list[MetricResult]]:
    """入口。返回 (runs_file, list[MetricResult])，并把结果落到
    ``reports/<runs_basename>/_scores.json``。
    """
    if runs_file is None:
        runs_file = latest_runs_file()
        if runs_file is None:
            raise RuntimeError("找不到 runs 文件，请先跑 `python -m eval rag run`")
        print(f"未指定输入，取最新：{runs_file.relative_to(PROJECT_ROOT)}")

    records = load_records(runs_file)
    if not records:
        raise RuntimeError("runs 文件为空")

    warnings = sanity_check_doc_id_alignment(records)
    if warnings:
        print(
            f"\n⚠ chunk 维度 docId 与 contexts frontmatter 还原值不一致 "
            f"({len(warnings)}/{len(records)} 条样本)：",
            file=sys.stderr,
        )
        for w in warnings[:5]:
            print(f"  - {w['query_id']}: mismatches={w['mismatches'][:3]}", file=sys.stderr)
        if len(warnings) > 5:
            print(f"  ... 另 {len(warnings) - 5} 条", file=sys.stderr)
        print(
            "  → 检查 ragent EvalController 的 chunkId → docId 映射，"
            "或 t_knowledge_chunk.doc_id 与 t_knowledge_document.doc_name 是否一致\n",
            file=sys.stderr,
        )

    results: list[MetricResult] = []
    print("[1/4] intent ...")
    results += intent.compute(records)
    print("[2/4] retrieval ...")
    results += retrieval.compute(records)
    print("[3/4] behavior ...")
    results += behavior.compute(records)
    print("[4/4] latency ...")
    results += latency.compute(records)

    if not skip_ragas:
        print("[5/5] ragas (LLM-as-judge) ...")
        try:
            from eval.rag.metrics import ragas_judge

            results += ragas_judge.compute(records, limit=ragas_limit, n_runs=ragas_n)
        except ImportError as exc:
            print(f"⚠ 跳过 RAGAS（依赖未安装：{exc}）", file=sys.stderr)
        except RuntimeError as exc:
            print(f"⚠ 跳过 RAGAS：{exc}", file=sys.stderr)
            print(
                "  → 想算 RAGAS 时：export AIHUBMIX_API_KEY=<your_key> 后再跑 "
                "`python -m eval rag score`（复用已有 runs，不必重跑 runner）",
                file=sys.stderr,
            )
        except Exception as exc:
            print(f"⚠ 跳过 RAGAS（执行失败：{type(exc).__name__}: {exc}）", file=sys.stderr)
            print(
                "  → 自建指标仍会落盘；修正 judge/embedding 配置后"
                "可复用同一 runs 文件重跑 score。",
                file=sys.stderr,
            )

    report_dir = REPORTS_DIR / runs_file.stem
    report_dir.mkdir(parents=True, exist_ok=True)
    scores_path = report_dir / "_scores.json"
    payload = {
        "runs_file": str(runs_file),
        "n_records": len(records),
        "status": status_distribution(records),
        "sanity_warnings": warnings,
        "metrics": [dataclasses.asdict(m) for m in results],
    }
    scores_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n落盘：{scores_path.relative_to(PROJECT_ROOT)}")

    _print_summary(results)
    return runs_file, results


def _print_summary(results: list[MetricResult]) -> None:
    print("\n=== 整体指标 ===")
    for m in results:
        v = m.overall
        if v is None:
            s = "—"
        elif m.name.endswith("_ms"):
            s = f"{int(v)}"
        elif m.is_pct:
            s = f"{v * 100:.1f}%"
        else:
            s = f"{v:.3f}"
        print(f"  {m.name:<24s} = {s}")

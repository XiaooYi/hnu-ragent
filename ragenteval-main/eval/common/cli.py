"""评测 CLI 唯一入口。

    python -m eval rag run [--limit 20]        调 ragent，落 runs/v1_<ts>.jsonl
    python -m eval rag score [--skip-ragas]    算所有指标
    python -m eval rag report [--theme swiss]  出 report.md + per_sample.csv + failures.jsonl + slides.html
    python -m eval rag diff <run_a> <run_b>    A/B 指标对比
    python -m eval rag all                     run → score → report 一条龙

实现约定：所有子命令的 handler 都是几行胶水，真正的逻辑在 pipeline/ 和 report/。
不引入 click/typer，标准库 argparse 足够。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def cmd_run(args: argparse.Namespace) -> int:
    from eval.rag.pipeline.runner import run

    try:
        out_path = run(
            limit=args.limit,
            start=args.start,
            sleep=args.sleep,
            workers=args.workers,
            filter_intent=args.filter_intent,
            debug=args.debug,
        )
        return 0 if out_path.name else 1
    except RuntimeError as e:
        print(f"错误：{e}", file=sys.stderr)
        return 2


def cmd_score(args: argparse.Namespace) -> int:
    from eval.rag.pipeline.score import score

    runs_file = _resolve_runs_file(args.runs_file)
    score(runs_file=runs_file, skip_ragas=args.skip_ragas, ragas_limit=args.ragas_limit, ragas_n=args.ragas_n)
    return 0


def _resolve_runs_file(raw: str | Path | None) -> Path | None:
    """解析用户传入的 runs 文件参数：支持完整路径 / 文件名 / stem / report 目录。"""
    from eval.rag.pipeline.score import REPORTS_DIR, RUNS_DIR, latest_runs_file

    if raw is None:
        return None
    p = Path(raw)
    if p.exists():
        if p.is_dir():
            # report 目录 → 推导 runs 文件
            scores = p / "_scores.json"
            if scores.exists():
                candidate = RUNS_DIR / f"{p.name}.jsonl"
                if candidate.exists():
                    return candidate
            return None
        return p
    # 尝试 eval/runs/<name>
    candidate = RUNS_DIR / p.name if p.suffix else RUNS_DIR / f"{p.name}.jsonl"
    if candidate.exists():
        return candidate
    # stem 匹配
    if not p.suffix:
        candidate2 = RUNS_DIR / f"{p.name}.jsonl"
        if candidate2.exists():
            return candidate2
    # 尝试 eval/reports/<name> → 推导 runs 文件
    report_dir = REPORTS_DIR / p.name
    scores = report_dir / "_scores.json"
    if scores.exists():
        candidate = RUNS_DIR / f"{p.name}.jsonl"
        if candidate.exists():
            return candidate
    return None


def cmd_report(args: argparse.Namespace) -> int:
    import json

    from eval.rag.pipeline.score import REPORTS_DIR, RUNS_DIR, latest_runs_file, load_records
    from eval.rag.report import markdown as md
    from eval.rag.report import slides
    from eval.common.schemas import MetricResult

    def latest_reportable_runs_file() -> Path | None:
        # 优先从 report 目录找最新的（已有评分结果的），退回到最新 runs 文件
        for report_dir in sorted(REPORTS_DIR.glob("v1_*"), reverse=True):
            scores = report_dir / "_scores.json"
            if not scores.exists():
                continue
            runs_candidate = RUNS_DIR / f"{report_dir.name}.jsonl"
            if runs_candidate.exists():
                return runs_candidate
        return latest_runs_file()

    runs_file = _resolve_runs_file(args.runs_file)
    if runs_file is None:
        runs_file = latest_reportable_runs_file()
        if runs_file is not None:
            report_dir = REPORTS_DIR / runs_file.stem
            print(f"未指定输入，取最新：{report_dir.relative_to(PROJECT_ROOT)}")
    if runs_file is None:
        print("找不到 runs 文件，请先跑 `python -m eval rag run`", file=sys.stderr)
        return 2

    report_dir = REPORTS_DIR / runs_file.stem
    scores_path = report_dir / "_scores.json"
    if not scores_path.exists():
        print(f"找不到 {scores_path}，请先跑 `python -m eval rag score`", file=sys.stderr)
        return 2

    payload = json.loads(scores_path.read_text(encoding="utf-8"))
    metrics = [MetricResult(**m) for m in payload["metrics"]]
    records = load_records(runs_file)
    status = payload.get("status", {})
    per_sample_path = report_dir / "per_sample.csv"
    manual_overrides = md.load_manual_overrides(per_sample_path)
    report_metrics = md.apply_manual_overrides(records, metrics, manual_overrides)
    if manual_overrides:
        count = sum(len(scores) for scores in manual_overrides.values())
        print(f"  manual:     loaded {count} override(s) from {per_sample_path.relative_to(PROJECT_ROOT)}")

    if not args.only_slides:
        outs = md.write_all(
            report_dir,
            runs_file,
            records,
            metrics,
            status,
            report_metrics=report_metrics,
            manual_overrides=manual_overrides,
        )
        for name, path in outs.items():
            print(f"  {name}: {path.relative_to(PROJECT_ROOT)}")

    slides_path = slides.write(report_dir, runs_file, records, report_metrics, theme=args.theme)
    print(f"  slides.html: {slides_path.relative_to(PROJECT_ROOT)}")
    print(f"  latest:      eval/reports/latest_slides.html")
    return 0


def cmd_diff(args: argparse.Namespace) -> int:
    from eval.rag.report.diff import compare

    try:
        out = compare(args.run_a, args.run_b, out_md=args.out_md)
        print(out)
        return 0
    except FileNotFoundError as e:
        print(f"错误：{e}", file=sys.stderr)
        return 2


def cmd_all(args: argparse.Namespace) -> int:
    from eval.rag.pipeline.runner import run as run_runner
    from eval.rag.pipeline.score import score

    try:
        runs_file = run_runner(limit=args.limit, sleep=args.sleep, workers=args.workers)
    except RuntimeError as e:
        print(f"错误：{e}", file=sys.stderr)
        return 2

    if not runs_file.name:
        return 1

    score(runs_file=runs_file, skip_ragas=args.skip_ragas, ragas_limit=args.ragas_limit, ragas_n=args.ragas_n)

    args_report = argparse.Namespace(runs_file=runs_file, theme=args.theme, only_slides=False)
    return cmd_report(args_report)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="eval", description="比特严选 RAG 评测套件")
    sub = parser.add_subparsers(dest="command", required=True)

    p_rag = sub.add_parser("rag", help="RAG 评测")
    rag_sub = p_rag.add_subparsers(dest="rag_command", required=True)

    p_run = rag_sub.add_parser("run", help="调 ragent 跑评测")
    p_run.add_argument("--limit", type=int, default=20, help="只跑前 N 条")
    p_run.add_argument("--start", type=int, default=0, help="跳过前 N 条")
    p_run.add_argument("--sleep", type=float, default=0.3, help="每条之间等待秒数")
    p_run.add_argument("-w", "--workers", type=int, default=1, help="并行线程数（默认 1 顺序）")
    p_run.add_argument("--filter-intent", default=None, help="只跑指定 intent_l2 的样本")
    p_run.add_argument("--debug", action="store_true", help="保留每条 query 的原始 SSE 字节流")
    p_run.set_defaults(func=cmd_run)

    p_score = rag_sub.add_parser("score", help="基于最新 runs 算所有指标")
    p_score.add_argument("runs_file", type=Path, nargs="?", default=None)
    p_score.add_argument("--skip-ragas", action="store_true", help="跳过 LLM-as-judge，省 API 调用")
    p_score.add_argument("--ragas-limit", type=int, default=None, help="RAGAS 只评前 N 条")
    p_score.add_argument(
        "--ragas-n",
        type=int,
        default=1,
        help="RAGAS 独立并发跑 N 次取均值（不使用 API n 参数）",
    )
    p_score.set_defaults(func=cmd_score)

    p_report = rag_sub.add_parser("report", help="出 markdown / csv / slides")
    p_report.add_argument(
        "runs_file", type=Path, nargs="?", default=None,
        help="runs 文件或 report 目录（如 eval/reports/v1_20260528_140944），不指定则取最新",
    )
    p_report.add_argument("--theme", default="swiss", choices=["swiss", "magazine"])
    p_report.add_argument("--only-slides", action="store_true", help="只重出 slides.html")
    p_report.set_defaults(func=cmd_report)

    p_diff = rag_sub.add_parser("diff", help="A/B 指标对比（两份 _scores.json）")
    p_diff.add_argument("run_a", help="基准 run 名或 _scores.json 路径")
    p_diff.add_argument("run_b", help="对比 run 名或 _scores.json 路径")
    p_diff.add_argument("-o", "--out-md", type=Path, default=None, help="同时输出 markdown")
    p_diff.set_defaults(func=cmd_diff)

    p_all = rag_sub.add_parser("all", help="run → score → report 一条龙")
    p_all.add_argument("--limit", type=int, default=20)
    p_all.add_argument("--sleep", type=float, default=0.3)
    p_all.add_argument("-w", "--workers", type=int, default=1, help="并行线程数（默认 1 顺序）")
    p_all.add_argument("--skip-ragas", action="store_true")
    p_all.add_argument("--ragas-limit", type=int, default=None)
    p_all.add_argument(
        "--ragas-n",
        type=int,
        default=1,
        help="RAGAS 独立并发跑 N 次取均值（不使用 API n 参数）",
    )
    p_all.add_argument("--theme", default="swiss", choices=["swiss", "magazine"])
    p_all.set_defaults(func=cmd_all)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

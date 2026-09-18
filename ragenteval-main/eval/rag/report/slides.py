"""16:9 测评汇报 HTML（guizang-ppt-skill 模板）。

输入：评测 records + 指标 metrics（来自 pipeline.score）。
输出：``reports/<run>/slides.html`` + ``reports/latest_slides.html``。

页面顺序：
    P1 hero dark     封面
    P2 light         核心 KPI 大字报（意图 Top-1 / Hit@5 / Answer Correctness / Faithfulness）
    P3 dark          次级 KPI（Recall@5 / Ctx Recall / Ctx Precision / Answer Relevancy / MRR / TTFT / 误拒 / 误召回）
    P4 hero light    幕封：检索篇章
    P5 light         按二级意图分层（rowline 表）
    P6+ light/dark   失败样例（每页 4 个 stat-card 风格卡）
    Pn light         样本明细表
    Pn+1 hero dark   收束（金句 + 下一步）
"""
from __future__ import annotations

import html
import shutil
import statistics
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence

from eval.common.schemas import EvalRecord, MetricResult

PROJECT_ROOT = Path(__file__).resolve().parents[3]
REPORTS_DIR = PROJECT_ROOT / "eval" / "reports"
TEMPLATES_DIR = PROJECT_ROOT / "eval" / "rag" / "templates"
THEME_TEMPLATES = {
    "swiss": TEMPLATES_DIR / "swiss_template.html",
    "magazine": TEMPLATES_DIR / "magazine_template.html",
}
DEFAULT_THEME = "swiss"

FAILURES_PER_PAGE = 4
SAMPLES_PER_PAGE = 8
INTENT_ROWS_PER_PAGE = 6


# ============ data view ============


def _view(records: list[EvalRecord], metrics: list[MetricResult]) -> dict[str, Any]:
    """把 metrics + records 转换成模板需要的 flat dict（含 overall / ragas / by_intent_l2）。"""
    idx = {m.name: m for m in metrics}

    def ov(name: str) -> float | None:
        return idx[name].overall if name in idx else None

    def ov_int(name: str) -> int | None:
        v = ov(name)
        return int(v) if v is not None else None

    n = len(records)
    status = dict(Counter(r.final_status or "unknown" for r in records))

    # 按 intent_l2 重新算 count（n / retrieval_n 不是 MetricResult 字段）
    intent_groups: dict[str, list[EvalRecord]] = {}
    for r in records:
        intent_groups.setdefault(r.intent_l2 or "?", []).append(r)
    by_intent_l2: dict[str, dict[str, Any]] = {}
    for intent, sub in intent_groups.items():
        sub_retrieval = [r for r in sub if r.requires_rag and r.reference_doc_ids]
        ttfts = [r.first_token_ms or r.latency_ms or 0 for r in sub if (r.first_token_ms or r.latency_ms)]
        by_intent_l2[intent] = {
            "n": len(sub),
            "retrieval_n": len(sub_retrieval),
            "hit@5": idx["hit@5"].by_intent_l2.get(intent) if "hit@5" in idx else None,
            "recall@5": idx["recall@5"].by_intent_l2.get(intent) if "recall@5" in idx else None,
            "mrr@10": idx["mrr@10"].by_intent_l2.get(intent) if "mrr@10" in idx else None,
            "ttft_mean": f"{statistics.mean(ttfts):.0f} ms" if ttfts else None,
        }

    overall = {
        "n": n,
        "status": status,
        "intent_top1_acc": ov("intent_top1"),
        "hit@5": ov("hit@5"),
        "recall@5": ov("recall@5"),
        "mrr@10": ov("mrr@10"),
        "refusal_when_required": ov("refusal_when_required"),
        "fallback_when_required": ov("fallback_when_required"),
        "over_retrieval_rate": ov("over_retrieval_rate"),
        "ttft_mean": ov_int("ttft_mean_ms"),
        "total_mean": ov_int("total_mean_ms"),
    }
    ragas = {
        k: ov(k)
        for k in ("faithfulness", "answer_relevancy", "answer_correctness",
                  "context_precision", "context_recall")
    }
    return {"overall": overall, "ragas": ragas, "by_intent_l2": by_intent_l2, "idx": idx}


# ============ formatting helpers ============


def esc(v: Any) -> str:
    return html.escape("" if v is None else str(v), quote=True)


def pct(v: float | None, digits: int = 1) -> str:
    return "—" if v is None else f"{v * 100:.{digits}f}%"


def num(v: Any, digits: int = 3) -> str:
    if v is None or v == "":
        return "—"
    if isinstance(v, int):
        return str(v)
    return f"{v:.{digits}f}"


def chunks(items: Sequence[Any], size: int) -> Iterable[Sequence[Any]]:
    for i in range(0, len(items), size):
        yield items[i : i + size]


def truncate(text: str, n: int) -> str:
    text = (text or "").strip()
    return text if len(text) <= n else text[:n].rstrip() + "…"


# ============ slide builders ============


def slide_cover(run_file: Path, overall: dict[str, Any], generated_at: str) -> str:
    n = overall.get("n", 0)
    status = overall.get("status") or {}
    status_text = " · ".join(f"{k} {v}" for k, v in sorted(status.items()))
    return f"""
<section class="slide hero dark">
  <div class="chrome">
    <div>RAG · 全链路评测 · v1</div>
    <div>BitSelect / 2026</div>
  </div>
  <div class="frame" style="display:grid; gap:4vh; align-content:center; min-height:80vh">
    <div class="kicker" data-anim>Bit Select · RAG 测评汇报</div>
    <h1 class="h-hero" data-anim>比特严选</h1>
    <h2 class="h-sub" data-anim>RAG 客服助手 · 真实链路评测</h2>
    <p class="lead" style="max-width:62vw" data-anim>
      意图 → 检索 → 生成 · 三段全链路的可量化评测；自建指标与 RAGAS 互为印证。
    </p>
    <div class="meta-row" data-anim>
      <span>{esc(n)} 条样本</span><span>·</span>
      <span>{esc(status_text or '—')}</span><span>·</span>
      <span>{esc(generated_at)}</span>
    </div>
  </div>
  <div class="foot">
    <div>{esc(run_file.name)}</div>
    <div>— · —</div>
  </div>
</section>
"""


def stat_card(label: str, big: str, note: str) -> str:
    return f"""
      <div class="stat-card" data-anim>
        <div class="stat-label">{esc(label)}</div>
        <div class="stat-nb">{big}</div>
        <div class="stat-note">{esc(note)}</div>
      </div>
    """


def slide_hero_kpi(overall: dict[str, Any], ragas: dict[str, Any]) -> str:
    def big_pct(v):
        return "—" if v is None else f'{v * 100:.1f}<span class="stat-unit">%</span>'

    def big_num(v):
        return "—" if v is None else f"{v:.2f}"

    return f"""
<section class="slide light">
  <div class="chrome">
    <div>核心 KPI · Headline</div>
    <div>Act I · 01</div>
  </div>
  <div class="frame" style="padding-top:5vh">
    <div class="kicker" data-anim>四个上线决定项</div>
    <h2 class="h-xl" data-anim>核心指标</h2>
    <p class="lead" style="margin-top:2vh;max-width:62vw" data-anim>
      意图 → 检索 → 答案事实性 → 答案忠实性。任何一环掉，结论就站不住。
    </p>

    <div class="grid-4" style="margin-top:5vh">
      {stat_card("意图 Top-1 准确率", big_pct(overall.get("intent_top1_acc")), "上游闸门 · 错则全错")}
      {stat_card("检索 Hit@5", big_pct(overall.get("hit@5")), "至少命中 1 条期望文档")}
      {stat_card("Answer Correctness", big_num(ragas.get("answer_correctness")), "回答与参考答案的语义+事实一致")}
      {stat_card("Faithfulness", big_num(ragas.get("faithfulness")), "回答忠实于召回（幻觉检测）")}
    </div>
  </div>
  <div class="foot">
    <div>Hero KPI · 4 / {esc(overall.get('n') or 0)} 样本</div>
    <div>— · —</div>
  </div>
</section>
"""


def _secondary_card(label: str, big: str, note: str) -> str:
    return f"""
      <div class="kpi-cell" data-anim>
        <div class="kpi-label">{esc(label)}</div>
        <div class="kpi-value">{big}</div>
        <div class="kpi-desc">{esc(note)}</div>
      </div>
    """


def slide_secondary_kpi(overall: dict[str, Any], ragas: dict[str, Any]) -> str:
    def big_pct(v):
        return "—" if v is None else f'{v * 100:.1f}<span class="kpi-unit">%</span>'

    def big_ms(v):
        return "—" if v is None else f'{v}<span class="kpi-unit">ms</span>'

    def big_n(v):
        return "—" if v is None else f"{v:.2f}"

    cards = [
        ("Recall@5", big_pct(overall.get("recall@5")), "召回覆盖率 · 期望 ≥ 85%"),
        ("MRR@10", big_n(overall.get("mrr@10")), "首个命中文档排名质量"),
        ("Context Recall", big_n(ragas.get("context_recall")), "上下文覆盖参考答案"),
        ("Context Precision", big_n(ragas.get("context_precision")), "上下文有用信息比例"),
        ("Answer Relevancy", big_n(ragas.get("answer_relevancy")), "回答与问题切题度"),
        ("首字均值", big_ms(overall.get("ttft_mean")), "TTFT · 对话体感卡点"),
        ("误拒率", big_pct(overall.get("refusal_when_required")), "requires_rag 却 0 召回"),
        ("误召回率", big_pct(overall.get("over_retrieval_rate")), "!requires_rag 却走 RAG"),
        ("整流均值", big_ms(overall.get("total_mean")), "完整流式耗时 · 参考"),
    ]
    cards_html = "\n".join(_secondary_card(label, val, note) for label, val, note in cards)

    return f"""
<section class="slide dark" style="padding-top:0;padding-bottom:0">
  <style>
    .kpi-wrap{{
      margin:auto 0;width:100%;
    }}
    .kpi-wrap .chrome{{
      margin-bottom:24px;
    }}
    .kpi-grid{{
      display:grid;
      grid-template-columns:repeat(3,1fr);
      column-gap:60px;
      row-gap:40px;
      padding:0 80px;
      position:relative;
    }}
    .kpi-grid::before,.kpi-grid::after{{
      content:"";
      position:absolute;top:0;bottom:0;
      width:1px;
      background:rgba(255,255,255,0.06);
    }}
    .kpi-grid::before{{left:calc(33.333% - 30px);}}
    .kpi-grid::after{{left:calc(66.666% - 30px);}}
    .kpi-cell{{
      text-align:left;
    }}
    .kpi-label{{
      font-family:var(--mono);
      font-size:11px;letter-spacing:.12em;text-transform:uppercase;
      color:rgba(255,255,255,.4);
    }}
    .kpi-value{{
      font-family:"Inter","DIN","Helvetica Neue",var(--sans);
      font-size:56px;font-weight:800;line-height:1.0;
      font-variant-numeric:tabular-nums;
      font-feature-settings:"tnum";
      color:#fff;margin-top:8px;
    }}
    .kpi-unit{{
      font-size:.4em;font-weight:600;opacity:.7;vertical-align:baseline;
    }}
    .kpi-desc{{
      font-family:var(--sans-zh);
      font-size:13px;color:rgba(255,255,255,.55);line-height:1.4;
      margin-top:6px;
    }}
    .sec-title{{
      font-family:var(--serif-zh);
      font-size:48px;font-weight:900;line-height:1.08;
    }}
    .kpi-foot{{
      font-family:var(--mono);
      font-size:max(11px,.78vw);letter-spacing:.18em;text-transform:uppercase;
      opacity:.5;margin-top:32px;padding:0 80px;
    }}
    .kpi-wrap .foot{{
      margin-top:4px;
    }}
    @media(max-width:768px){{
      .kpi-grid{{grid-template-columns:repeat(2,1fr);padding:0 40px}}
      .kpi-foot{{padding:0 40px}}
      .kpi-grid::before,.kpi-grid::after{{display:none}}
    }}
    @media(max-width:480px){{
      .kpi-grid{{grid-template-columns:1fr;padding:0 24px}}
      .kpi-foot{{padding:0 24px}}
    }}
  </style>
  <div class="kpi-wrap">
    <div class="chrome">
      <div>次级 KPI · Detailed</div>
      <div>Act I · 02</div>
    </div>
    <div class="kicker" data-anim style="margin-bottom:8px;font-size:13px">立体度 · 性能 · 行为</div>
    <h2 class="sec-title" data-anim>次级指标</h2>
    <div class="kpi-grid" style="margin-top:40px">
      {cards_html}
    </div>
    <div class="kpi-foot">Detailed · 9 项次级指标</div>
    <div class="foot">
      <div></div>
      <div>— · —</div>
    </div>
  </div>
</section>
"""


def slide_act_retrieval() -> str:
    return """
<section class="slide hero light">
  <div class="chrome">
    <div>第二幕 · 检索切片</div>
    <div>Act II</div>
  </div>
  <div class="frame" style="display:grid; gap:6vh; align-content:center; min-height:80vh">
    <div class="kicker" data-anim>Act II</div>
    <h1 class="h-hero" style="font-size:8.5vw" data-anim>检索分层</h1>
    <p class="lead" style="max-width:55vw" data-anim>
      按二级意图打开，看每条链路的真实表现。
    </p>
  </div>
  <div class="foot">
    <div>Act II · 入口</div>
    <div>— · —</div>
  </div>
</section>
"""


def render_intent_row(intent: str, agg: dict[str, Any]) -> str:
    metrics_text = (
        f"Hit@5 <b>{pct(agg.get('hit@5'))}</b>"
        f" · Recall@5 <b>{pct(agg.get('recall@5'))}</b>"
        f" · MRR <b>{num(agg.get('mrr@10'))}</b>"
    )
    return f"""
    <div class="rowline" data-anim style="padding:2.0vh 0">
      <div class="k">{esc(intent)}</div>
      <div class="v" style="line-height:1.38">{metrics_text}<br>
        <span style="opacity:.55">n = {esc(agg['n'])} · retrieval_n = {esc(agg.get('retrieval_n', 0))}</span>
      </div>
      <div class="m">{esc(agg.get('ttft_mean') or '—')}</div>
    </div>
    """


def slides_by_intent(by_intent: dict[str, dict[str, Any]]) -> list[str]:
    rows = sorted(by_intent.items())
    pages = list(chunks(rows, INTENT_ROWS_PER_PAGE)) or [[]]
    out = []
    for idx, page in enumerate(pages, start=1):
        title_suffix = "" if len(pages) == 1 else f" · {idx}/{len(pages)}"
        rows_html = "".join(render_intent_row(intent, agg) for intent, agg in page)
        out.append(f"""
<section class="slide light">
  <div class="chrome">
    <div>按二级意图分层{title_suffix}</div>
    <div>Act II · 03</div>
  </div>
  <div class="frame" style="padding-top:5vh">
    <div class="kicker" data-anim>Intent Breakdown</div>
    <h2 class="h-xl" style="font-size:4.2vw" data-anim>每条链路的真实表现</h2>
    <div style="margin-top:4vh">{rows_html}</div>
  </div>
  <div class="foot">
    <div>意图名 · Hit/Recall/MRR · 首字均值</div>
    <div>— · —</div>
  </div>
</section>
""")
    return out


def failure_card(r: EvalRecord) -> str:
    question = truncate(r.user_input, 60)
    ref = ", ".join(r.reference_doc_ids[:4])
    got = ", ".join(r.retrieved_doc_ids[:4])
    response = truncate(r.response, 100)
    return f"""
    <div class="stat-card" data-anim style="gap:.4vh;padding-top:1.2vh">
      <div class="stat-label" style="display:flex;justify-content:space-between;align-items:center">
        <span>{esc(r.query_id)} · {esc(r.intent_l2)}</span>
      </div>
      <div style="font-family:var(--serif-zh);font-weight:600;font-size:max(15px,1.25vw);line-height:1.32;margin-top:.6vh;letter-spacing:.005em">
        {esc(question)}
      </div>
      <div style="font-family:var(--mono);font-size:max(10px,.78vw);letter-spacing:.04em;text-transform:none;opacity:.7;margin-top:1.2vh;line-height:1.55">
        <span style="opacity:.55">期望</span> {esc(ref) or "—"}<br>
        <span style="opacity:.55">召回</span> {esc(got) or "—"}
      </div>
      <div style="font-family:var(--sans-zh);font-size:max(12px,.95vw);line-height:1.55;opacity:.7;margin-top:1.2vh">
        {esc(response)}
      </div>
    </div>
    """


def slides_failures(failed: list[EvalRecord]) -> list[str]:
    if not failed:
        return ["""
<section class="slide light">
  <div class="chrome">
    <div>失败样例 · Miss Analysis</div>
    <div>Act II · 04</div>
  </div>
  <div class="frame center" style="padding-top:8vh">
    <div class="kicker" data-anim>Clean Sweep</div>
    <h2 class="h-xl" data-anim>本次没有失败样例。</h2>
    <p class="lead" style="margin-top:3vh;max-width:62vw" data-anim>
      Hit@5、correctness、误拒、过召回四项均无失败；继续盯指标的"质"而非"有/无"。
    </p>
  </div>
  <div class="foot">
    <div>无失败样例</div>
    <div>— · —</div>
  </div>
</section>
"""]
    pages = list(chunks(failed, FAILURES_PER_PAGE))
    out = []
    for idx, page in enumerate(pages, start=1):
        suffix = "" if len(pages) == 1 else f" · {idx}/{len(pages)}"
        cards_html = "".join(failure_card(r) for r in page)
        theme = "dark" if idx % 2 == 0 else "light"
        out.append(f"""
<section class="slide {theme}">
  <div class="chrome">
    <div>失败样例 · Miss Analysis{suffix}</div>
    <div>Act II · {esc(4 + idx - 1)}</div>
  </div>
  <div class="frame" style="padding-top:4.5vh">
    <div class="kicker" data-anim>失败样例一览</div>
    <h2 class="h-xl" style="font-size:4.2vw" data-anim>哪些被漏了</h2>
    <div class="grid-4" style="margin-top:4vh;gap:3vh 3vw">
      {cards_html}
    </div>
  </div>
  <div class="foot">
    <div>共 {esc(len(failed))} 条失败样例 · 每页 4 张卡</div>
    <div>— · —</div>
  </div>
</section>
""")
    return out


def render_sample_row(r: EvalRecord, idx: dict[str, MetricResult]) -> str:
    hit5_v = idx["hit@5"].per_sample.get(r.query_id) if "hit@5" in idx else None
    hit_text = "不计" if hit5_v is None else ("是" if hit5_v else "否")
    recall5_v = idx["recall@5"].per_sample.get(r.query_id) if "recall@5" in idx else None
    faith_v = idx["faithfulness"].per_sample.get(r.query_id) if "faithfulness" in idx else None
    correct_v = idx["answer_correctness"].per_sample.get(r.query_id) if "answer_correctness" in idx else None
    return f"""
    <div class="rowline" data-anim style="grid-template-columns:1fr 2.5fr 1fr;padding:1.6vh 0">
      <div class="k" style="font-size:1.2vw">{esc(r.query_id)}</div>
      <div class="v" style="line-height:1.45;font-size:max(13px,1.05vw)">
        <span style="opacity:.6">{esc(r.intent_l2)}</span>
        · Hit@5 <b>{hit_text}</b>
        · Recall@5 <b>{pct(recall5_v)}</b>
        · Faith. <b>{num(faith_v)}</b>
        · Correct. <b>{num(correct_v)}</b>
      </div>
      <div class="m">{esc(r.first_token_ms or '—')} ms</div>
    </div>
    """


def slides_sample_detail(records: list[EvalRecord], idx: dict[str, MetricResult]) -> list[str]:
    pages = list(chunks(records, SAMPLES_PER_PAGE)) or [[]]
    out = []
    for page_idx, page in enumerate(pages, start=1):
        suffix = "" if len(pages) == 1 else f" · {page_idx}/{len(pages)}"
        rows_html = "".join(render_sample_row(r, idx) for r in page)
        out.append(f"""
<section class="slide light">
  <div class="chrome">
    <div>样本明细 · Sample Detail{suffix}</div>
    <div>Act III</div>
  </div>
  <div class="frame" style="padding-top:5vh">
    <div class="kicker" data-anim>Per-Sample</div>
    <h2 class="h-xl" style="font-size:4.2vw" data-anim>逐条对照</h2>
    <div style="margin-top:6vh">{rows_html}</div>
  </div>
  <div class="foot">
    <div>ID · Intent · 命中/正确/忠实 · 首字耗时</div>
    <div>— · —</div>
  </div>
</section>
""")
    return out


def slide_closing(overall: dict[str, Any], ragas: dict[str, Any]) -> str:
    def fmt_pct(v):
        return "—" if v is None else f"{v * 100:.0f}%"

    def fmt_num(v):
        return "—" if v is None else f"{v:.2f}"

    headline = "意图打住、检索打稳、生成打实。"
    next_step = "下一步：用更大评估集追跑、扩 ground_truth 字段、把 首字均值压进 6s。"
    return f"""
<section class="slide hero dark">
  <div class="chrome">
    <div>收束 · Takeaway</div>
    <div>Final</div>
  </div>
  <div class="frame" style="display:grid; gap:6vh; align-content:center; min-height:80vh">
    <div class="kicker" data-anim>Takeaway</div>
    <h1 class="h-hero" style="font-size:7.6vw;line-height:1.05" data-anim>
      <span style="display:block">{esc(headline)}</span>
    </h1>
    <div class="grid-4" style="margin-top:2vh;gap:3vh 3vw">
      <div class="stat-card" data-anim style="border-color:rgba(var(--paper-rgb),.25)">
        <div class="stat-label">Intent</div>
        <div class="stat-nb" style="font-size:4.6vw">{fmt_pct(overall.get("intent_top1_acc"))}</div>
        <div class="stat-note">Top-1 准确率</div>
      </div>
      <div class="stat-card" data-anim style="border-color:rgba(var(--paper-rgb),.25)">
        <div class="stat-label">Retrieve</div>
        <div class="stat-nb" style="font-size:4.6vw">{fmt_pct(overall.get("hit@5"))}</div>
        <div class="stat-note">Hit@5</div>
      </div>
      <div class="stat-card" data-anim style="border-color:rgba(var(--paper-rgb),.25)">
        <div class="stat-label">Generate · Correct</div>
        <div class="stat-nb" style="font-size:4.6vw">{fmt_num(ragas.get("answer_correctness"))}</div>
        <div class="stat-note">Answer Correctness</div>
      </div>
      <div class="stat-card" data-anim style="border-color:rgba(var(--paper-rgb),.25)">
        <div class="stat-label">Generate · Faithful</div>
        <div class="stat-nb" style="font-size:4.6vw">{fmt_num(ragas.get("faithfulness"))}</div>
        <div class="stat-note">Faithfulness</div>
      </div>
    </div>
    <p class="lead" style="max-width:60vw" data-anim>{esc(next_step)}</p>
  </div>
  <div class="foot">
    <div>— end —</div>
    <div>BitSelect · RAG</div>
  </div>
</section>
"""


# ============ orchestration ============


def build_slides(
    run_file: Path,
    records: list[EvalRecord],
    metrics: list[MetricResult],
) -> str:
    view = _view(records, metrics)
    overall, ragas, by_intent, idx = view["overall"], view["ragas"], view["by_intent_l2"], view["idx"]
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M")

    from eval.rag.report.markdown import get_failure_qids

    failure_qids = get_failure_qids(records, metrics)
    failed = [r for r in records if r.query_id in failure_qids]

    pieces = [
        slide_cover(run_file, overall, generated_at),
        slide_hero_kpi(overall, ragas),
        slide_secondary_kpi(overall, ragas),
        slide_act_retrieval(),
    ]
    pieces.extend(slides_by_intent(by_intent))
    pieces.extend(slides_failures(failed))
    pieces.extend(slides_sample_detail(records, idx))
    pieces.append(slide_closing(overall, ragas))
    return "\n".join(pieces)


def render_deck(slides_html: str, theme: str = DEFAULT_THEME) -> str:
    template_path = THEME_TEMPLATES.get(theme)
    if template_path is None:
        raise RuntimeError(f"未知主题 '{theme}'，可选：{', '.join(sorted(THEME_TEMPLATES))}")
    template = template_path.read_text(encoding="utf-8")
    if "<!-- SLIDES_HERE -->" not in template:
        raise RuntimeError(f"模板缺少 <!-- SLIDES_HERE --> 占位符：{template_path}")
    return template.replace("<!-- SLIDES_HERE -->", slides_html, 1)


def write(
    report_dir: Path,
    run_file: Path,
    records: list[EvalRecord],
    metrics: list[MetricResult],
    theme: str = DEFAULT_THEME,
    update_latest: bool = True,
) -> Path:
    report_dir.mkdir(parents=True, exist_ok=True)
    slides_html = build_slides(run_file, records, metrics)
    deck = render_deck(slides_html, theme=theme)
    out_path = report_dir / "slides.html"
    out_path.write_text(deck, encoding="utf-8")
    if update_latest:
        shutil.copyfile(out_path, REPORTS_DIR / "latest_slides.html")
    return out_path

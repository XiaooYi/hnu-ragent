"""评测数据模型 —— 教程从这里开始读。

整套评测就是把数据沿着这三个类型搬一遍：

    eval_set_v1.jsonl
         │  load_samples()
         ▼
    List[EvalSample]   ← 评估集一条样本（输入）
         │  pipeline.runner.run()
         ▼
    List[EvalRecord]   ← 跑完 ragent 拿回来的一条完整记录
         │  pipeline.score.score()
         ▼
    List[MetricResult] ← 每个指标一个结果
         │  report.markdown / report.slides
         ▼
    report.md / per_sample.csv / failures.jsonl / slides.html

所有脚本通过这三个 dataclass 交换数据，禁止裸 dict。新增字段在这里加一次，
所有读写两端都能看到。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal, Optional

FinalStatus = Literal["success", "refused", "error", "cancelled", "unknown"]


@dataclass
class EvalSample:
    """评估集的一条样本（输入）。字段对齐 dataset/eval_set_v1.jsonl 的 schema。"""

    query_id: str
    query: str
    intent_l1: str
    intent_l2: str
    difficulty: str
    requires_rag: bool
    expected_doc_ids: list[str]
    expected_doc_ids_nice: list[str] = field(default_factory=list)
    ground_truth: str = ""
    expected_answer_type: Optional[str] = None
    trap_type: Optional[str] = None

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "EvalSample":
        return cls(
            query_id=d["query_id"],
            query=d["query"],
            intent_l1=d.get("intent_l1", ""),
            intent_l2=d.get("intent_l2", ""),
            difficulty=d.get("difficulty", "medium"),
            requires_rag=bool(d.get("requires_rag", False)),
            expected_doc_ids=list(d.get("expected_doc_ids") or []),
            expected_doc_ids_nice=list(d.get("expected_doc_ids_nice") or []),
            ground_truth=d.get("ground_truth") or "",
            expected_answer_type=d.get("expected_answer_type"),
            trap_type=d.get("trap_type"),
        )


@dataclass
class EvalRecord:
    """一次 runner 跑完后的完整记录（runs/*.jsonl 一行 = 一个 EvalRecord）。

    字段分三段：评估集复制过来的、/rag/v3/chat 拿到的、/rag/eval 拿到的。
    """

    query_id: str
    user_input: str
    reference: str
    reference_doc_ids: list[str]
    reference_doc_ids_nice: list[str]
    intent_l1: str
    intent_l2: str
    difficulty: str
    requires_rag: bool

    response: str
    thinking: Optional[str]
    latency_ms: int
    first_token_ms: Optional[int]
    final_status: FinalStatus
    error: Optional[str]
    conversation_id: Optional[str]
    task_id: Optional[str]

    retrieved_doc_ids: list[str]
    retrieved_doc_ids_raw: list[str]
    retrieved_chunk_ids: list[str]
    retrieved_contexts: list[str]
    retrieved_context_doc_ids: list[Optional[str]]
    intent_pred: Optional[str]
    intent_pred_all: list[str]
    has_kb: Optional[bool]
    has_mcp: Optional[bool]
    trace_id: Optional[str]

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "EvalRecord":
        known = {f for f in cls.__dataclass_fields__}
        kwargs = {k: v for k, v in d.items() if k in known}
        try:
            return cls(**kwargs)
        except TypeError as exc:
            missing = [f for f in cls.__dataclass_fields__ if f not in kwargs]
            hint = f"（缺失字段：{missing}）" if missing else ""
            raise TypeError(
                f"EvalRecord.from_dict 失败：缺少必填字段或类型不匹配 {hint}"
                f"\n  已有字段：{sorted(kwargs.keys())}"
                f"\n  原始错误：{exc}"
            ) from exc


@dataclass
class MetricResult:
    """一个指标的输出。所有 metrics/*.py 的 compute() 函数都返回这个。

    - overall:      单一数值（比例/均值/分位数），不可算时 None
    - by_intent_l1/l2: 分层均值，画分层表用
    - per_sample:   query_id → 分数，per_sample.csv 按它拼接
    - meta:         其它说明字段（状态分布、跳过原因等），可空
    """

    name: str
    overall: Optional[float]
    by_intent_l1: dict[str, Optional[float]] = field(default_factory=dict)
    by_intent_l2: dict[str, Optional[float]] = field(default_factory=dict)
    per_sample: dict[str, Optional[float]] = field(default_factory=dict)
    meta: dict[str, Any] = field(default_factory=dict)
    is_pct: bool = True


def load_samples(dataset_path) -> list[EvalSample]:
    """从 jsonl 读评估集，每行一个 EvalSample。"""
    import json
    from pathlib import Path

    samples: list[EvalSample] = []
    with Path(dataset_path).open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                samples.append(EvalSample.from_dict(json.loads(line)))
    return samples

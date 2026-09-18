"""Runner —— 调 ragent 两个接口跑评测集，落 runs/v1_<ts>.jsonl。

行为：
    1. 登录拿 sa-token
    2. 读评估集 + doc_id_map.json（反向映射 ragent_doc_id -> 业务 id）
    3. 对每条 query 调两个接口：
       - SSE: GET /rag/v3/chat（真实生产链路）→ response / thinking
       - JSON: GET /rag/eval（评测旁路，需 app.eval.enabled=true）→ 检索证据
    4. 每条样本落一条 EvalRecord（dataclass）到 runs/*.jsonl
    5. 支持 --workers N 多线程并行（默认 1，保持顺序行为）

入口：``run(...)`` 返回产物路径。CLI 解析参数后直接调它。
"""
from __future__ import annotations

import concurrent.futures
import dataclasses
import json
import os
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator

import requests

from eval.common.schemas import EvalRecord, EvalSample, load_samples

DEFAULT_BASE_URL = "http://localhost:9090/api/ragent"

PROJECT_ROOT = Path(__file__).resolve().parents[3]
EVAL_SET_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "eval_set_v1.jsonl"
DOC_MAP_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "doc_id_map.json"
RUNS_DIR = PROJECT_ROOT / "eval" / "runs"

LOGIN_TIMEOUT = 15
SSE_CONNECT_TIMEOUT = 15
SSE_READ_TIMEOUT = 300
EVAL_CONNECT_TIMEOUT = 15
EVAL_READ_TIMEOUT = 180


def login(base_url: str, username: str, password: str) -> str:
    resp = requests.post(
        f"{base_url}/auth/login",
        json={"username": username, "password": password},
        timeout=LOGIN_TIMEOUT,
    )
    resp.raise_for_status()
    token = (resp.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"登录响应里没拿到 token：{resp.text}")
    return token


def parse_sse_stream(byte_iter: Iterator[bytes]) -> Iterator[tuple[str, str]]:
    """从字节流里逐事件 yield (event_name, data_string)。

    自己切事件而不用 requests.iter_lines()：后者在事件分隔符 (`\\n\\n`)
    跨 HTTP chunk 边界时会把空行吞掉，导致中间事件丢失。
    """
    buffer = ""
    for chunk in byte_iter:
        if not chunk:
            continue
        buffer += chunk.decode("utf-8", errors="replace")
        while True:
            idx_crlf = buffer.find("\r\n\r\n")
            idx_lf = buffer.find("\n\n")
            if idx_crlf == -1 and idx_lf == -1:
                break
            if idx_crlf == -1:
                idx, sep_len = idx_lf, 2
            elif idx_lf == -1:
                idx, sep_len = idx_crlf, 4
            else:
                if idx_crlf < idx_lf:
                    idx, sep_len = idx_crlf, 4
                else:
                    idx, sep_len = idx_lf, 2
            event_block = buffer[:idx]
            buffer = buffer[idx + sep_len:]
            yield from _parse_event_block(event_block)

    if buffer.strip():
        yield from _parse_event_block(buffer)


def _parse_event_block(block: str) -> Iterator[tuple[str, str]]:
    event_name = "message"
    data_lines: list[str] = []
    for raw in block.splitlines():
        line = raw.rstrip("\r")
        if not line or line.startswith(":"):
            continue
        if ":" not in line:
            continue
        field, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            event_name = value
        elif field == "data":
            data_lines.append(value)
    if data_lines:
        yield event_name, "\n".join(data_lines)


def stream_chat_one_query(
    base_url: str,
    token: str,
    query: str,
    debug_raw_path: Path | None = None,
) -> dict[str, Any]:
    """走 /rag/v3/chat SSE，聚合 response / thinking / final_status / first_token_ms。"""
    params = {"question": query}
    headers = {"Authorization": token, "Accept": "text/event-stream"}

    state: dict[str, Any] = {
        "response": "",
        "thinking": "",
        "meta": None,
        "final_status": "unknown",
        "error": None,
        "first_token_ms": None,
    }
    raw_fp = debug_raw_path.open("wb") if debug_raw_path else None
    start = time.time()
    try:
        with requests.get(
            f"{base_url}/rag/v3/chat",
            params=params,
            headers=headers,
            stream=True,
            timeout=(SSE_CONNECT_TIMEOUT, SSE_READ_TIMEOUT),
        ) as resp:
            resp.raise_for_status()

            def byte_chunks() -> Iterator[bytes]:
                for chunk in resp.iter_content(chunk_size=512):
                    if not chunk:
                        continue
                    if raw_fp is not None:
                        raw_fp.write(chunk)
                        raw_fp.flush()
                    yield chunk

            for event_name, data_str in parse_sse_stream(byte_chunks()):
                payload: Any = data_str
                if data_str and data_str != "[DONE]":
                    try:
                        payload = json.loads(data_str)
                    except json.JSONDecodeError:
                        pass

                if event_name == "meta":
                    state["meta"] = payload
                elif event_name == "message" and isinstance(payload, dict):
                    delta_type = payload.get("type")
                    content = payload.get("delta", "") or ""
                    # 体感卡点是正式回答首字到达（type=response），不算 think 链路。
                    if delta_type == "response":
                        if state["first_token_ms"] is None and content:
                            state["first_token_ms"] = int((time.time() - start) * 1000)
                        state["response"] += content
                    elif delta_type == "think":
                        state["thinking"] += content
                elif event_name == "finish":
                    state["final_status"] = "success"
                elif event_name == "reject":
                    state["final_status"] = "refused"
                    if isinstance(payload, dict):
                        state["error"] = payload.get("message") or json.dumps(payload, ensure_ascii=False)
                elif event_name == "cancel":
                    state["final_status"] = "cancelled"
                elif event_name == "done":
                    break
    except Exception as exc:  # noqa: BLE001
        state["error"] = str(exc)
        if state["final_status"] == "unknown":
            state["final_status"] = "error"
    finally:
        if raw_fp is not None:
            raw_fp.close()

    state["latency_ms"] = int((time.time() - start) * 1000)
    return state


def fetch_eval_retrieval(base_url: str, token: str, query: str) -> dict[str, Any]:
    """走 GET /rag/eval，取回检索证据（docIds / chunkIds / contexts / intent）。"""
    headers = {"Authorization": token, "Accept": "application/json"}
    params = {"question": query}

    state: dict[str, Any] = {
        "retrieved_doc_ids_ragent": [],
        "retrieved_chunk_ids": [],
        "retrieved_contexts": [],
        "retrieved_context_doc_ids": [],
        "intent_leaf_ids": [],
        "has_kb": None,
        "has_mcp": None,
        "trace_id": None,
        "error": None,
    }
    try:
        resp = requests.get(
            f"{base_url}/rag/eval",
            params=params,
            headers=headers,
            timeout=(EVAL_CONNECT_TIMEOUT, EVAL_READ_TIMEOUT),
        )
        resp.raise_for_status()
        envelope = resp.json()
        if not envelope.get("success"):
            state["error"] = envelope.get("message") or "ragent /rag/eval 返回非 success"
            return state
        data = envelope.get("data") or {}
        state["retrieved_doc_ids_ragent"] = data.get("retrievedDocIds") or []
        state["retrieved_chunk_ids"] = data.get("retrievedChunkIds") or []
        state["retrieved_contexts"] = data.get("retrievedContexts") or []
        state["retrieved_context_doc_ids"] = data.get("retrievedContextDocIds") or []
        state["intent_leaf_ids"] = data.get("intentLeafIds") or []
        state["has_kb"] = data.get("hasKb")
        state["has_mcp"] = data.get("hasMcp")
        state["trace_id"] = data.get("traceId")
    except Exception as exc:  # noqa: BLE001
        state["error"] = str(exc)
    return state


def build_record(
    sample: EvalSample,
    chat_state: dict[str, Any],
    eval_state: dict[str, Any],
    ragent_to_biz: dict[str, str],
) -> EvalRecord:
    """合并静态评估字段 + 双接口产物，落成一条 EvalRecord。"""
    biz_doc_ids = [ragent_to_biz.get(d, d) for d in eval_state["retrieved_doc_ids_ragent"]]
    biz_context_doc_ids = [
        None if d is None else ragent_to_biz.get(d, d)
        for d in eval_state["retrieved_context_doc_ids"]
    ]
    intent_codes = list(eval_state["intent_leaf_ids"])
    intent_pred = next((c for c in intent_codes if c), None)
    meta = chat_state["meta"] or {}

    return EvalRecord(
        query_id=sample.query_id,
        user_input=sample.query,
        reference=sample.ground_truth,
        reference_doc_ids=sample.expected_doc_ids,
        reference_doc_ids_nice=sample.expected_doc_ids_nice,
        intent_l1=sample.intent_l1,
        intent_l2=sample.intent_l2,
        difficulty=sample.difficulty,
        requires_rag=sample.requires_rag,
        response=chat_state["response"],
        thinking=chat_state["thinking"] or None,
        latency_ms=chat_state["latency_ms"],
        first_token_ms=chat_state["first_token_ms"],
        final_status=chat_state["final_status"],
        error=chat_state["error"] or eval_state["error"],
        conversation_id=meta.get("conversationId"),
        task_id=meta.get("taskId"),
        retrieved_doc_ids=biz_doc_ids,
        retrieved_doc_ids_raw=eval_state["retrieved_doc_ids_ragent"],
        retrieved_chunk_ids=eval_state["retrieved_chunk_ids"],
        retrieved_contexts=eval_state["retrieved_contexts"],
        retrieved_context_doc_ids=biz_context_doc_ids,
        intent_pred=intent_pred,
        intent_pred_all=intent_codes,
        has_kb=eval_state["has_kb"],
        has_mcp=eval_state["has_mcp"],
        trace_id=eval_state["trace_id"],
    )


def load_ragent_to_biz_map(doc_map_path: Path = DOC_MAP_PATH) -> dict[str, str]:
    doc_map = json.loads(doc_map_path.read_text(encoding="utf-8"))
    return {v["ragent_doc_id"]: biz for biz, v in doc_map.items()}


def _process_one(
    sample: EvalSample,
    idx: int,
    total: int,
    base_url: str,
    token: str,
    ragent_to_biz: dict[str, str],
    debug: bool,
) -> tuple[int, EvalRecord, str]:
    """处理单条样本，返回 (序号, record, 预览文本)。独立函数便于 ThreadPoolExecutor 调度。"""
    preview = (sample.query[:40] + "…") if len(sample.query) > 40 else sample.query
    debug_path = RUNS_DIR / f"debug_{sample.query_id}.sse" if debug else None
    chat_state = stream_chat_one_query(base_url, token, sample.query, debug_raw_path=debug_path)
    eval_state = fetch_eval_retrieval(base_url, token, sample.query)
    record = build_record(sample, chat_state, eval_state, ragent_to_biz)
    return idx, record, preview


def run(
    *,
    limit: int = 20,
    start: int = 0,
    sleep: float = 0.3,
    workers: int = 1,
    filter_intent: str | None = None,
    debug: bool = False,
    out_path: Path | None = None,
) -> Path:
    """主入口。返回 runs/*.jsonl 的路径。环境变量：
    RAGENT_BASE_URL / RAGENT_USERNAME / RAGENT_PASSWORD。
    """
    base_url = os.environ.get("RAGENT_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    username = os.environ.get("RAGENT_USERNAME")
    password = os.environ.get("RAGENT_PASSWORD")
    if not username or not password:
        raise RuntimeError("缺少环境变量 RAGENT_USERNAME / RAGENT_PASSWORD")

    if not DOC_MAP_PATH.exists():
        raise RuntimeError(f"找不到 {DOC_MAP_PATH}，请先跑 eval/rag/init/upload_docs.py")

    ragent_to_biz = load_ragent_to_biz_map()
    samples = load_samples(EVAL_SET_PATH)
    if filter_intent:
        samples = [s for s in samples if s.intent_l2 == filter_intent]
    samples = samples[start : start + limit]
    if not samples:
        print("没有可执行的样本", file=sys.stderr)
        return Path()

    RUNS_DIR.mkdir(exist_ok=True)
    out_path = out_path or (RUNS_DIR / f"v1_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jsonl")

    print(f"登录 {base_url} ...")
    token = login(base_url, username, password)
    print("OK\n")
    print(f"将跑 {len(samples)} 条，落 {out_path.relative_to(PROJECT_ROOT)}\n")

    stats = {"success": 0, "refused": 0, "error": 0, "cancelled": 0, "unknown": 0}
    with out_path.open("w", encoding="utf-8") as out_fp:
        if workers <= 1:
            # 单线程 —— 保持原有顺序逻辑
            for idx, sample in enumerate(samples, start=1):
                preview = (sample.query[:40] + "…") if len(sample.query) > 40 else sample.query
                print(
                    f"  [{idx:>2d}/{len(samples)}] {sample.query_id:<8s} {preview!r}",
                    end="", flush=True,
                )

                debug_path = RUNS_DIR / f"debug_{sample.query_id}.sse" if debug else None
                chat_state = stream_chat_one_query(
                    base_url, token, sample.query, debug_raw_path=debug_path,
                )
                eval_state = fetch_eval_retrieval(base_url, token, sample.query)
                record = build_record(sample, chat_state, eval_state, ragent_to_biz)
                out_fp.write(json.dumps(dataclasses.asdict(record), ensure_ascii=False) + "\n")
                out_fp.flush()

                status = chat_state["final_status"]
                stats[status] = stats.get(status, 0) + 1
                ttft = record.first_token_ms
                print(
                    f"  -> {status:>9s}  docs={len(record.retrieved_doc_ids):<2d} "
                    f"ctx={len(record.retrieved_contexts):<2d} "
                    f"resp={len(record.response):<5d} "
                    f"ttft={ttft if ttft is not None else '?':>5}ms "
                    f"total={record.latency_ms:>6d}ms"
                )

                if record.error:
                    print(f"        ⚠ {record.error}", file=sys.stderr)

                if sleep > 0:
                    time.sleep(sleep)
        else:
            # 多线程并行
            write_lock = threading.Lock()
            print_lock = threading.Lock()

            with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
                future_map = {}
                for idx, sample in enumerate(samples, start=1):
                    future = executor.submit(
                        _process_one,
                        sample, idx, len(samples),
                        base_url, token, ragent_to_biz, debug,
                    )
                    future_map[future] = idx

                for future in concurrent.futures.as_completed(future_map):
                    idx, record, preview = future.result()

                    with write_lock:
                        out_fp.write(
                            json.dumps(dataclasses.asdict(record), ensure_ascii=False) + "\n"
                        )
                        out_fp.flush()

                    status = record.final_status
                    ttft = record.first_token_ms

                    with print_lock:
                        stats[status] = stats.get(status, 0) + 1
                        print(
                            f"  [{idx:>2d}/{len(samples)}] {record.query_id:<8s} {preview!r}"
                            f"  -> {status:>9s}  docs={len(record.retrieved_doc_ids):<2d} "
                            f"ctx={len(record.retrieved_contexts):<2d} "
                            f"resp={len(record.response):<5d} "
                            f"ttft={ttft if ttft is not None else '?':>5}ms "
                            f"total={record.latency_ms:>6d}ms"
                        )
                        if record.error:
                            print(f"        ⚠ {record.error}", file=sys.stderr)

    print(f"\n完成。统计：{dict(stats)}")
    print(f"产物：{out_path}")
    return out_path

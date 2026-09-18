"""Step 3: 构建并灌入意图识别树。

行为：
    1. 从评估集 + doc_id_map.json 数据驱动算出每个 intent_l2 的多数派 KB
    2. 拉取每个 intent_l2 的真实 query 作为 examples（最多 5 条）
    3. 拼出 DOMAIN(3) -> CATEGORY(5) -> TOPIC(22) 共 30 个节点的入参
    4. 按 DOMAIN -> CATEGORY -> TOPIC 顺序逐个 POST /intent-tree
    5. 写 intent_ids.json：intentCode -> ragent 内部 node id

业务规则：
    - F2/F3/C1/C2 是 SYSTEM kind（系统话术，不走 RAG），kbId 留空
    - 其他 18 个 leaf 是 KB kind，kbId 按数据投票
    - L0/L1 节点 kbId 一律为空（服务端只在 TOPIC+KB 时强校验）

幂等：基于 intent_ids.json 跳过已创建 intentCode。

环境变量：与 create_kbs.py 一致。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import requests

DEFAULT_BASE_URL = "http://localhost:9090/api/ragent"

PROJECT_ROOT = Path(__file__).resolve().parents[3]
INIT_DIR = Path(__file__).resolve().parent
KB_IDS_PATH = INIT_DIR / "kb_ids.json"
DOC_MAP_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "doc_id_map.json"
EVAL_SET_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "eval_set_v1.jsonl"
INTENT_IDS_PATH = INIT_DIR / "intent_ids.json"

REQUEST_TIMEOUT = 30

# ragent IntentKind 枚举
KIND_KB = 0
KIND_SYSTEM = 1
KIND_MCP = 2

# ragent IntentLevel 枚举
LEVEL_DOMAIN = 0
LEVEL_CATEGORY = 1
LEVEL_TOPIC = 2

# Top-K 默认值（仅 KB-kind leaf 用）
DEFAULT_TOP_K = 5

# 不走 RAG 的 leaf 白名单（kind=SYSTEM）
SYSTEM_LEAF_CODES = {"F2_功能建议", "F3_投诉吐槽", "C1_寒暄问候", "C2_越界提问"}

# 树骨架定义：(intentCode, name, parentCode)
DOMAINS = [
    ("SUPPORT", "产品咨询"),
    ("FEEDBACK", "用户反馈"),
    ("CHAT", "闲聊兜底"),
]

CATEGORIES = [
    # (intentCode, name, parent_domain)
    ("SUPPORT_PRESALE", "售前咨询", "SUPPORT"),
    ("SUPPORT_USAGE", "使用咨询", "SUPPORT"),
    ("SUPPORT_AFTERSALES", "售后咨询", "SUPPORT"),
    ("FEEDBACK_ALL", "反馈处理", "FEEDBACK"),
    ("CHAT_ALL", "兜底对话", "CHAT"),
]

# leaf 归属：(intentCode, name, parent_category)
TOPICS = [
    ("S1_选购推荐", "选购推荐", "SUPPORT_PRESALE"),
    ("S2_参数咨询", "参数咨询", "SUPPORT_PRESALE"),
    ("S3_对比选购", "对比选购", "SUPPORT_PRESALE"),
    ("S4_价格活动", "价格活动", "SUPPORT_PRESALE"),
    ("S5_库存到货", "库存到货", "SUPPORT_PRESALE"),
    ("S6_配件兼容", "配件兼容", "SUPPORT_PRESALE"),
    ("S7_适用场景", "适用场景", "SUPPORT_PRESALE"),
    ("S8_操作指引", "操作指引", "SUPPORT_USAGE"),
    ("S9_配网连接", "配网连接", "SUPPORT_USAGE"),
    ("S10_APP功能", "APP 功能", "SUPPORT_USAGE"),
    ("S11_固件升级", "固件升级", "SUPPORT_USAGE"),
    ("S12_生态联动", "生态联动", "SUPPORT_USAGE"),
    ("S13_保养维护", "保养维护", "SUPPORT_USAGE"),
    ("S14_售后政策", "售后政策", "SUPPORT_AFTERSALES"),
    ("S15_退换货", "退换货", "SUPPORT_AFTERSALES"),
    ("S16_物流配送", "物流配送", "SUPPORT_AFTERSALES"),
    ("S17_发票会员", "发票/会员", "SUPPORT_AFTERSALES"),
    ("F1_故障报告", "故障报告", "FEEDBACK_ALL"),
    ("F2_功能建议", "功能建议", "FEEDBACK_ALL"),
    ("F3_投诉吐槽", "投诉吐槽", "FEEDBACK_ALL"),
    ("C1_寒暄问候", "寒暄问候", "CHAT_ALL"),
    ("C2_越界提问", "越界提问", "CHAT_ALL"),
]


def login(base_url: str, username: str, password: str) -> str:
    resp = requests.post(
        f"{base_url}/auth/login",
        json={"username": username, "password": password},
        timeout=REQUEST_TIMEOUT,
    )
    resp.raise_for_status()
    token = (resp.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"登录响应里没拿到 token：{resp.text}")
    return token


def vote_kb_per_leaf(
    eval_path: Path, doc_map: dict[str, dict[str, Any]]
) -> dict[str, str]:
    """对每个 intent_l2 算 expected_doc_ids 的多数派 KB。"""
    votes: dict[str, Counter] = defaultdict(Counter)
    with eval_path.open(encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            intent = r["intent_l2"]
            for doc_id in r.get("expected_doc_ids") or []:
                entry = doc_map.get(doc_id)
                if entry:
                    votes[intent][entry["kb_key"]] += 1
    return {k: c.most_common(1)[0][0] for k, c in votes.items() if c}


def sample_queries_per_leaf(
    eval_path: Path, limit: int = 5
) -> dict[str, list[str]]:
    """每个 intent_l2 取最多 limit 条 query 做 examples。"""
    out: dict[str, list[str]] = defaultdict(list)
    with eval_path.open(encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            intent = r["intent_l2"]
            if len(out[intent]) < limit:
                out[intent].append(r["query"])
    return dict(out)


def make_description(name: str, examples: list[str]) -> str:
    """MVP 简化版：用 leaf name + 第一条示例。AI 增强留到后续脚本做。"""
    base = f"用户的{name}相关问题"
    if examples:
        base += f"，例如：{examples[0]}"
    boundary = BOUNDARY_DISAMBIGUATION.get(name)
    if boundary:
        base += f"。{boundary}"
    return base


# 相邻易混淆意图的硬编码区分说明（补充到 leaf description 中供 LLM 区分）
BOUNDARY_DISAMBIGUATION: dict[str, str] = {
    "S4_价格活动": "注意：涉及“价保补差”“7天内降价退差价”的问题属于本分类，不属于 S14_售后政策。售后政策只涉及保修、维修、换屏报价等硬件服务问题，不涉及购买前的价格变动补偿",
    "S5_库存到货": "注意：涉及“下单后多久能送到”“大概几天到货”的送达时效预估问题属于本分类，不属于 S16_物流配送。S16_物流配送只涉及发货后的承运商状态跟踪（物流轨迹、改地址、破损签收），不涉及下单后的预计送达时间",
    "S14_售后政策": "注意：本分类只涉及保修期、保修范围、碎屏维修报价、过保维修等硬件售后服务。“价保补差”“降价退差”等价格补偿不属于本分类，应归入 S4_价格活动",
    "S16_物流配送": "注意：本分类只涉及发货后的承运商物流问题（改地址、破损、指定送货时间）。“下单后多久能送到”“现货几天到”等送达时效预估不属于本分类，应归入 S5_库存到货",
}


def build_payloads(
    kb_ids: dict[str, dict[str, str]],
    kb_per_leaf: dict[str, str],
    examples_per_leaf: dict[str, list[str]],
) -> list[dict[str, Any]]:
    """生成 30 个节点的 IntentNodeCreateRequest payload（按依赖顺序）。"""
    payloads: list[dict[str, Any]] = []

    for sort_order, (code, name) in enumerate(DOMAINS):
        payloads.append(
            {
                "intentCode": code,
                "name": name,
                "level": LEVEL_DOMAIN,
                "parentCode": None,
                "kbId": None,
                "kind": KIND_KB,  # 非 leaf，kind 实际不被使用，默认 0
                "description": f"一级意图：{name}",
                "examples": [],
                "topK": None,
                "sortOrder": sort_order,
                "enabled": 1,
            }
        )

    for sort_order, (code, name, parent) in enumerate(CATEGORIES):
        payloads.append(
            {
                "intentCode": code,
                "name": name,
                "level": LEVEL_CATEGORY,
                "parentCode": parent,
                "kbId": None,
                "kind": KIND_KB,
                "description": f"二级分组：{name}",
                "examples": [],
                "topK": None,
                "sortOrder": sort_order,
                "enabled": 1,
            }
        )

    for sort_order, (code, name, parent) in enumerate(TOPICS):
        is_system = code in SYSTEM_LEAF_CODES
        kind = KIND_SYSTEM if is_system else KIND_KB
        examples = examples_per_leaf.get(code, [])
        kb_id: str | None = None
        if not is_system:
            kb_key = kb_per_leaf.get(code)
            if not kb_key:
                print(
                    f"[WARN] leaf {code} ({name}) 在 eval set 中无样本，跳过该 leaf"
                )
                continue
            kb_id = kb_ids[kb_key]["kb_id"]
        payloads.append(
            {
                "intentCode": code,
                "name": name,
                "level": LEVEL_TOPIC,
                "parentCode": parent,
                "kbId": kb_id,
                "kind": kind,
                "description": make_description(name, examples),
                "examples": examples,
                "topK": DEFAULT_TOP_K if not is_system else None,
                "sortOrder": sort_order,
                "enabled": 1,
            }
        )

    return payloads


def create_node(base_url: str, token: str, payload: dict[str, Any]) -> str:
    resp = requests.post(
        f"{base_url}/intent-tree",
        json=payload,
        headers={"Authorization": token, "Content-Type": "application/json"},
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code != 200:
        raise RuntimeError(
            f"POST /intent-tree failed HTTP {resp.status_code}: {resp.text}"
        )
    body = resp.json()
    node_id = body.get("data")
    if not node_id:
        raise RuntimeError(f"响应没拿到 node id：{body}")
    return node_id


def load_intent_ids() -> dict[str, str]:
    if INTENT_IDS_PATH.exists():
        return json.loads(INTENT_IDS_PATH.read_text(encoding="utf-8"))
    return {}


def save_intent_ids(m: dict[str, str]) -> None:
    INTENT_IDS_PATH.write_text(
        json.dumps(m, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Step 3: 构建并灌入意图识别树")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印将要创建的节点，不实际请求",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="删除 intent_ids.json 全量重建（默认跳过已创建的节点以支持断点续传）",
    )
    args = parser.parse_args()

    if not KB_IDS_PATH.exists():
        print(f"找不到 {KB_IDS_PATH}，请先跑 create_kbs.py", file=sys.stderr)
        return 2
    if not DOC_MAP_PATH.exists():
        print(f"找不到 {DOC_MAP_PATH}，请先跑 upload_docs.py", file=sys.stderr)
        return 2

    kb_ids = json.loads(KB_IDS_PATH.read_text(encoding="utf-8"))
    doc_map = json.loads(DOC_MAP_PATH.read_text(encoding="utf-8"))

    kb_per_leaf = vote_kb_per_leaf(EVAL_SET_PATH, doc_map)
    examples_per_leaf = sample_queries_per_leaf(EVAL_SET_PATH)

    payloads = build_payloads(kb_ids, kb_per_leaf, examples_per_leaf)

    # 反向映射：kb_id -> kb_key，方便 dry-run 输出
    kb_id_to_key = {v["kb_id"]: k for k, v in kb_ids.items()}

    print(f"将创建 {len(payloads)} 个节点：")
    for p in payloads:
        kb_tag = f" kb={kb_id_to_key.get(p['kbId'], '?')}" if p["kbId"] else ""
        kind_tag = {0: "KB", 1: "SYSTEM", 2: "MCP"}[p["kind"]]
        level_tag = {0: "D", 1: "C", 2: "T"}[p["level"]]
        ex_n = len(p["examples"])
        parent = f"  parent={p['parentCode']}" if p["parentCode"] else ""
        print(
            f"  [{level_tag}] {p['intentCode']:<20s}  kind={kind_tag:<6s}{kb_tag:<14s}  examples={ex_n}{parent}"
        )

    # 汇总分布
    kind_count = Counter(p["kind"] for p in payloads if p["level"] == LEVEL_TOPIC)
    kb_count = Counter(
        kb_id_to_key.get(p["kbId"]) for p in payloads if p["kbId"]
    )
    print("\n--- 汇总 ---")
    print(f"  DOMAIN: 3, CATEGORY: 5, TOPIC: 22")
    print(
        f"  TOPIC by kind: KB={kind_count[KIND_KB]}, SYSTEM={kind_count[KIND_SYSTEM]}, MCP={kind_count[KIND_MCP]}"
    )
    print(f"  KB leaves by KB: {dict(kb_count)}")

    if args.dry_run:
        print("\n--dry-run，未提交。")
        return 0

    base_url = os.environ.get("RAGENT_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    username = os.environ.get("RAGENT_USERNAME")
    password = os.environ.get("RAGENT_PASSWORD")
    if not username or not password:
        print("缺少环境变量 RAGENT_USERNAME / RAGENT_PASSWORD", file=sys.stderr)
        return 2

    print(f"\n登录 {base_url} ...")
    token = login(base_url, username, password)
    print("OK\n")

    if args.force and INTENT_IDS_PATH.exists():
        INTENT_IDS_PATH.unlink()
        print(f"已删除 {INTENT_IDS_PATH}，将全量重建\n")

    intent_ids = load_intent_ids()
    success = 0
    failed: list[tuple[str, str]] = []
    skipped = 0
    for idx, payload in enumerate(payloads, start=1):
        code = payload["intentCode"]
        if code in intent_ids:
            skipped += 1
            continue
        try:
            node_id = create_node(base_url, token, payload)
            intent_ids[code] = node_id
            save_intent_ids(intent_ids)
            success += 1
            print(f"  [{idx:>2d}/{len(payloads)}] ✓ {code} -> {node_id}")
        except Exception as exc:  # noqa: BLE001
            failed.append((code, str(exc)))
            print(f"  [{idx:>2d}/{len(payloads)}] ✗ {code}  {exc}", file=sys.stderr)

    print(f"\n完成：成功 {success}，失败 {len(failed)}，跳过 {skipped}")
    if failed:
        return 1
    print(f"\n写入 {INTENT_IDS_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Step 2: 把 knowledge_base/ 的 md 灌入 ragent 的 4 个 KB。

行为：
    1. POST /auth/login 拿 sa-token
    2. 加载 Step 1 产出的 kb_ids.json
    3. 遍历 knowledge_base/，按顶级目录映射到对应 KB：
        01_product/ -> product
        02_manual/  -> manual
        03_policy/  -> policy
        04_faq/     -> faq
        _meta/      -> 跳过
    4. 对每个 .md：
        a. POST /knowledge-base/{kb-id}/docs/upload （multipart）
        b. POST /knowledge-base/docs/{doc-id}/chunk （异步触发嵌入入库）
    5. 增量写 eval/rag/dataset/doc_id_map.json：业务 doc_id -> ragent 内部 doc_id

幂等性：基于 doc_id_map.json 跳过已上传文件。重复跑只会补齐缺失。
        如需重灌，删除 doc_id_map.json 并清理 ragent 侧的同名文档。

环境变量：与 create_kbs.py 一致。

依赖：requests（已经被 langchain_openai 间接引入）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

import requests

DEFAULT_BASE_URL = "http://localhost:9090/api/ragent"

PROJECT_ROOT = Path(__file__).resolve().parents[3]  # ragenteval/
KB_ROOT = PROJECT_ROOT / "knowledge_base"
KB_IDS_PATH = Path(__file__).resolve().parent / "kb_ids.json"
DOC_MAP_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "doc_id_map.json"

DIR_TO_KB_KEY: dict[str, str] = {
    "01_product": "product",
    "02_manual": "manual",
    "03_policy": "policy",
    "04_faq": "faq",
}

CHUNK_STRATEGY = "structure_aware"
CHUNK_CONFIG = json.dumps(
    {"targetChars": 1400, "maxChars": 1800, "minChars": 600, "overlapChars": 0},
    ensure_ascii=False,
)

REQUEST_TIMEOUT = 30


def login(base_url: str, username: str, password: str) -> str:
    resp = requests.post(
        f"{base_url}/auth/login",
        json={"username": username, "password": password},
        timeout=REQUEST_TIMEOUT,
    )
    resp.raise_for_status()
    payload = resp.json()
    token = (payload.get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"登录响应里没拿到 token：{payload}")
    return token


def upload_one(
    base_url: str,
    token: str,
    kb_id: str,
    md_path: Path,
) -> str:
    """上传单个 md，返回 ragent 内部 doc_id。"""
    with md_path.open("rb") as fp:
        files = {
            "file": (md_path.name, fp, "text/markdown"),
        }
        form = {
            "sourceType": "file",
            "processMode": "chunk",
            "chunkStrategy": CHUNK_STRATEGY,
            "chunkConfig": CHUNK_CONFIG,
        }
        headers = {"Authorization": token}
        resp = requests.post(
            f"{base_url}/knowledge-base/{kb_id}/docs/upload",
            files=files,
            data=form,
            headers=headers,
            timeout=REQUEST_TIMEOUT,
        )
    if resp.status_code != 200:
        raise RuntimeError(
            f"upload {md_path.name} 失败 HTTP {resp.status_code}: {resp.text}"
        )
    payload = resp.json()
    doc = payload.get("data") or {}
    doc_id = doc.get("id")
    if not doc_id:
        raise RuntimeError(f"upload {md_path.name} 响应没拿到 id：{payload}")
    return doc_id


def trigger_chunk(base_url: str, token: str, doc_id: str) -> None:
    resp = requests.post(
        f"{base_url}/knowledge-base/docs/{doc_id}/chunk",
        headers={"Authorization": token},
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code != 200:
        raise RuntimeError(
            f"trigger chunk {doc_id} 失败 HTTP {resp.status_code}: {resp.text}"
        )


def collect_targets() -> list[tuple[str, str, Path]]:
    """返回 (kb_key, business_doc_id, md_path) 列表。"""
    targets: list[tuple[str, str, Path]] = []
    for dir_name, kb_key in DIR_TO_KB_KEY.items():
        sub_root = KB_ROOT / dir_name
        if not sub_root.is_dir():
            print(f"  ⚠️  目录不存在，跳过：{sub_root}", file=sys.stderr)
            continue
        for md_path in sorted(sub_root.rglob("*.md")):
            business_doc_id = md_path.stem  # 文件名去 .md 即业务 id
            targets.append((kb_key, business_doc_id, md_path))
    return targets


def load_kb_ids() -> dict[str, dict[str, str]]:
    if not KB_IDS_PATH.exists():
        raise RuntimeError(
            f"找不到 {KB_IDS_PATH}，请先跑 create_kbs.py"
        )
    return json.loads(KB_IDS_PATH.read_text(encoding="utf-8"))


def load_doc_map() -> dict[str, dict[str, Any]]:
    if DOC_MAP_PATH.exists():
        return json.loads(DOC_MAP_PATH.read_text(encoding="utf-8"))
    return {}


def save_doc_map(doc_map: dict[str, dict[str, Any]]) -> None:
    DOC_MAP_PATH.write_text(
        json.dumps(doc_map, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Step 2: 批量灌入 KB 文档")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印将要上传的文件清单，不实际请求",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="只处理前 N 个文件（用于冒烟测试）",
    )
    parser.add_argument(
        "--sleep",
        type=float,
        default=0.0,
        help="每个文件之间等待秒数（避开 ragent upload 信号量上限）",
    )
    args = parser.parse_args()

    base_url = os.environ.get("RAGENT_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    username = os.environ.get("RAGENT_USERNAME")
    password = os.environ.get("RAGENT_PASSWORD")
    if not args.dry_run and (not username or not password):
        print("缺少环境变量 RAGENT_USERNAME / RAGENT_PASSWORD", file=sys.stderr)
        return 2

    targets = collect_targets()
    if args.limit:
        targets = targets[: args.limit]

    by_kb: dict[str, int] = {}
    for kb_key, _, _ in targets:
        by_kb[kb_key] = by_kb.get(kb_key, 0) + 1

    print(f"扫描到 {len(targets)} 个 md，按 KB 分布：")
    for k, n in by_kb.items():
        print(f"  {k:>8s}: {n}")

    if args.dry_run:
        print("\n--dry-run，未上传。前 10 个示例：")
        for kb_key, biz_id, path in targets[:10]:
            print(f"  [{kb_key}] {biz_id}  <- {path.relative_to(PROJECT_ROOT)}")
        return 0

    kb_ids = load_kb_ids()
    doc_map = load_doc_map()
    skipped = sum(1 for _, biz_id, _ in targets if biz_id in doc_map)
    if skipped:
        print(f"已有 {skipped} 个在 doc_id_map.json 中，将跳过")

    print(f"\n登录 {base_url} ...")
    token = login(base_url, username, password)
    print("OK\n")

    success = 0
    failed: list[tuple[str, str]] = []
    for idx, (kb_key, biz_id, md_path) in enumerate(targets, start=1):
        if biz_id in doc_map:
            continue
        kb_id = kb_ids[kb_key]["kb_id"]
        try:
            ragent_doc_id = upload_one(base_url, token, kb_id, md_path)
            trigger_chunk(base_url, token, ragent_doc_id)
            doc_map[biz_id] = {
                "ragent_doc_id": ragent_doc_id,
                "kb_key": kb_key,
                "kb_id": kb_id,
                "rel_path": str(md_path.relative_to(PROJECT_ROOT)),
            }
            save_doc_map(doc_map)  # 增量保存，断点续传
            success += 1
            print(f"  [{idx:>3d}/{len(targets)}] {kb_key:>8s} {biz_id} -> {ragent_doc_id}")
        except Exception as exc:  # noqa: BLE001
            failed.append((biz_id, str(exc)))
            print(f"  [{idx:>3d}/{len(targets)}] {kb_key:>8s} {biz_id} ❌ {exc}", file=sys.stderr)
        if args.sleep > 0:
            time.sleep(args.sleep)

    print(f"\n完成：成功 {success}，失败 {len(failed)}，跳过 {skipped}")
    if failed:
        print("失败列表：", file=sys.stderr)
        for biz_id, msg in failed:
            print(f"  {biz_id}: {msg}", file=sys.stderr)
        return 1
    print(f"\n写入 {DOC_MAP_PATH}")
    print(
        "\n⚠️  chunk 是异步执行的。建议稍后调 "
        "GET /knowledge-base/{kb-id}/docs 查看每个文档的 chunkCount，"
        "或直接看 ragent 日志确认全部入库完成。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

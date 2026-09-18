"""清空 ragent 中所有知识库及其文档（破坏性！）。

行为：
    1. 登录拿 sa-token
    2. 分页拉取所有 KB
    3. 对每个 KB：分页拉取所有 doc，逐个 DELETE
    4. DELETE KB 本身
    5. 清理本地 kb_ids.json / doc_id_map.json / intent_ids.json

安全保护：
    - 默认 --dry-run 模式（只打印将要删的东西，不实际请求 DELETE）
    - 必须显式加 --yes 才执行删除
    - 删除前再次提示，等待 3 秒可 Ctrl-C 取消

正在分块的文档（status=RUNNING）会被自动跳过并提示。

注意：本脚本只清 KB 和文档。ragent 后端的意图树节点（intent_tree 表）请用
reset_intent_tree.py 单独清理，否则 leaf 节点会指向已删除的 KB ID。

环境变量：与 create_kbs.py 一致。
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

INIT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = INIT_DIR.parent.parent.parent
KB_IDS_PATH = INIT_DIR / "kb_ids.json"
DOC_MAP_PATH = PROJECT_ROOT / "eval" / "rag" / "dataset" / "doc_id_map.json"
INTENT_IDS_PATH = INIT_DIR / "intent_ids.json"

PAGE_SIZE = 100
REQUEST_TIMEOUT = 30


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


def list_all_kbs(base_url: str, token: str) -> list[dict[str, Any]]:
    headers = {"Authorization": token}
    out: list[dict[str, Any]] = []
    current = 1
    while True:
        resp = requests.get(
            f"{base_url}/knowledge-base",
            params={"current": current, "size": PAGE_SIZE},
            headers=headers,
            timeout=REQUEST_TIMEOUT,
        )
        resp.raise_for_status()
        page = resp.json().get("data") or {}
        records = page.get("records") or []
        out.extend(records)
        if len(records) < PAGE_SIZE:
            break
        current += 1
    return out


def list_all_docs(base_url: str, token: str, kb_id: str) -> list[dict[str, Any]]:
    headers = {"Authorization": token}
    out: list[dict[str, Any]] = []
    current = 1
    while True:
        resp = requests.get(
            f"{base_url}/knowledge-base/{kb_id}/docs",
            params={"current": current, "size": PAGE_SIZE},
            headers=headers,
            timeout=REQUEST_TIMEOUT,
        )
        resp.raise_for_status()
        page = resp.json().get("data") or {}
        records = page.get("records") or []
        out.extend(records)
        if len(records) < PAGE_SIZE:
            break
        current += 1
    return out


def delete_doc(base_url: str, token: str, doc_id: str) -> tuple[bool, str]:
    resp = requests.delete(
        f"{base_url}/knowledge-base/docs/{doc_id}",
        headers={"Authorization": token},
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code == 200:
        return True, ""
    return False, f"HTTP {resp.status_code}: {resp.text}"


def delete_kb(base_url: str, token: str, kb_id: str) -> tuple[bool, str]:
    resp = requests.delete(
        f"{base_url}/knowledge-base/{kb_id}",
        headers={"Authorization": token},
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code == 200:
        return True, ""
    return False, f"HTTP {resp.status_code}: {resp.text}"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="清空 ragent 所有 KB 和文档（破坏性！）"
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="确认执行删除。不加此参数时只 dry-run（不实际删除）",
    )
    parser.add_argument(
        "--keep-local",
        action="store_true",
        help="不删除本地 kb_ids.json / doc_id_map.json",
    )
    parser.add_argument(
        "--retry-running",
        type=int,
        default=3,
        help="文档处于 RUNNING 状态时的重试次数（每次间隔 5s），默认 3",
    )
    args = parser.parse_args()

    base_url = os.environ.get("RAGENT_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    username = os.environ.get("RAGENT_USERNAME")
    password = os.environ.get("RAGENT_PASSWORD")
    if not username or not password:
        print("缺少环境变量 RAGENT_USERNAME / RAGENT_PASSWORD", file=sys.stderr)
        return 2

    print(f"登录 {base_url} ...")
    token = login(base_url, username, password)
    print("OK\n")

    print("拉取所有 KB ...")
    kbs = list_all_kbs(base_url, token)
    if not kbs:
        print("没有任何 KB，无事可做。")
        return 0

    # 预扫描：统计每个 KB 下的文档数
    print(f"\n共 {len(kbs)} 个 KB，逐个统计文档数：")
    plan: list[tuple[dict[str, Any], list[dict[str, Any]]]] = []
    for kb in kbs:
        kb_id = kb["id"]
        docs = list_all_docs(base_url, token, kb_id)
        plan.append((kb, docs))
        print(f"  - [{kb_id}] {kb.get('name')}  -> {len(docs)} 个文档")

    total_docs = sum(len(docs) for _, docs in plan)
    print(f"\n总计：将删除 {len(kbs)} 个 KB，{total_docs} 个文档。")

    if not args.yes:
        print("\n--dry-run 模式（默认）。要真的执行，请加 --yes 重跑。")
        return 0

    print("\n⚠️  3 秒后开始删除，Ctrl-C 可取消 ...")
    try:
        for i in range(3, 0, -1):
            print(f"   {i}")
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n已取消。")
        return 130

    failed_docs: list[tuple[str, str]] = []
    failed_kbs: list[tuple[str, str]] = []

    for kb, docs in plan:
        kb_id = kb["id"]
        kb_name = kb.get("name", kb_id)
        print(f"\n→ 清空 KB [{kb_name}] ({kb_id})")

        for doc in docs:
            doc_id = doc["id"]
            doc_name = doc.get("docName", doc_id)
            attempts = 0
            while True:
                ok, err = delete_doc(base_url, token, doc_id)
                if ok:
                    print(f"    ✓ doc {doc_name}")
                    break
                if "正在分块中" in err and attempts < args.retry_running:
                    attempts += 1
                    print(f"    … doc {doc_name} RUNNING，5s 后重试（{attempts}/{args.retry_running}）")
                    time.sleep(5)
                    continue
                print(f"    ✗ doc {doc_name}  {err}", file=sys.stderr)
                failed_docs.append((doc_id, err))
                break

        # 文档清完后删 KB
        ok, err = delete_kb(base_url, token, kb_id)
        if ok:
            print(f"  ✓ KB {kb_name}")
        else:
            print(f"  ✗ KB {kb_name}  {err}", file=sys.stderr)
            failed_kbs.append((kb_id, err))

    print(f"\n完成：失败文档 {len(failed_docs)}，失败 KB {len(failed_kbs)}")

    if not args.keep_local:
        for path in (KB_IDS_PATH, DOC_MAP_PATH, INTENT_IDS_PATH):
            if path.exists():
                path.unlink()
                print(f"已删除本地：{path.relative_to(INIT_DIR.parent.parent.parent)}")

    return 0 if not failed_docs and not failed_kbs else 1


if __name__ == "__main__":
    raise SystemExit(main())

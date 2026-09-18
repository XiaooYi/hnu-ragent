"""清空 ragent 中所有意图树节点（破坏性！）。

行为：
    1. 读取本地 intent_ids.json 拿到 intentCode -> node_id 映射
    2. 登录拿 sa-token
    3. 按 TOPIC -> CATEGORY -> DOMAIN 顺序（child 先于 parent）逐个 DELETE
    4. 清理本地 intent_ids.json

安全保护：
    - 默认 --dry-run 模式（只打印将要删的东西，不实际请求 DELETE）
    - 必须显式加 --yes 才执行删除
    - 删除前再次提示，等待 3 秒可 Ctrl-C 取消

降级行为：
    - 本地 intent_ids.json 不存在或为空：报错退出，请手动到 ragent 后端清理
    - 单个节点 DELETE 失败不中断，最后打印失败列表
    - HTTP 404 视为成功（节点不存在 = 已清空）

环境变量：与 create_kbs.py 一致。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

# 复用 build_intent_tree 的骨架定义，确保删除顺序与创建顺序一致（反向）
from build_intent_tree import CATEGORIES, DOMAINS, TOPICS

DEFAULT_BASE_URL = "http://localhost:9090/api/ragent"

INIT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = INIT_DIR.parent.parent.parent
INTENT_IDS_PATH = INIT_DIR / "intent_ids.json"

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


def delete_node(base_url: str, token: str, node_id: str) -> tuple[bool, str]:
    resp = requests.delete(
        f"{base_url}/intent-tree/{node_id}",
        headers={"Authorization": token},
        timeout=REQUEST_TIMEOUT,
    )
    if resp.status_code in (200, 404):
        return True, ""
    return False, f"HTTP {resp.status_code}: {resp.text}"


def ordered_codes() -> list[tuple[str, str, str]]:
    """按删除顺序返回 (intentCode, displayName, level_tag)：TOPIC -> CATEGORY -> DOMAIN。"""
    out: list[tuple[str, str, str]] = []
    for code, name, _parent in TOPICS:
        out.append((code, name, "T"))
    for code, name, _parent in CATEGORIES:
        out.append((code, name, "C"))
    for code, name in DOMAINS:
        out.append((code, name, "D"))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(
        description="清空 ragent 所有意图树节点（破坏性！）"
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="确认执行删除。不加此参数时只 dry-run（不实际删除）",
    )
    parser.add_argument(
        "--keep-local",
        action="store_true",
        help="不删除本地 intent_ids.json",
    )
    args = parser.parse_args()

    if not INTENT_IDS_PATH.exists():
        print(f"找不到 {INTENT_IDS_PATH}", file=sys.stderr)
        print(
            "如果 ragent 后端仍有遗留意图节点，请手动调 /intent-tree/{id} "
            "或 truncate intent_tree 表。",
            file=sys.stderr,
        )
        return 2

    intent_ids: dict[str, str] = json.loads(
        INTENT_IDS_PATH.read_text(encoding="utf-8")
    )
    if not intent_ids:
        print(f"{INTENT_IDS_PATH} 是空的，无事可做。")
        return 0

    # 按 child-first 顺序排，只保留 intent_ids.json 里实际存在的节点
    plan: list[tuple[str, str, str, str]] = []  # (code, name, level_tag, node_id)
    seen: set[str] = set()
    for code, name, level_tag in ordered_codes():
        node_id = intent_ids.get(code)
        if node_id is None:
            continue
        plan.append((code, name, level_tag, node_id))
        seen.add(code)

    # 兜底：intent_ids.json 里有但骨架里没列出的（不应发生），按出现顺序追加
    extras = [(c, c, "?", nid) for c, nid in intent_ids.items() if c not in seen]
    if extras:
        print(
            f"[WARN] intent_ids.json 里有 {len(extras)} 个未知 intentCode（不在 "
            "build_intent_tree.py 的骨架中），将追加到末尾删除：",
        )
        for c, _, _, _ in extras:
            print(f"  - {c}")
    plan.extend(extras)

    print(f"\n将删除 {len(plan)} 个意图节点（child-first 顺序）：")
    for code, name, level_tag, node_id in plan:
        print(f"  [{level_tag}] {code:<20s}  ({name})  -> {node_id}")

    if not args.yes:
        print("\n--dry-run 模式（默认）。要真的执行，请加 --yes 重跑。")
        return 0

    base_url = os.environ.get("RAGENT_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    username = os.environ.get("RAGENT_USERNAME")
    password = os.environ.get("RAGENT_PASSWORD")
    if not username or not password:
        print("缺少环境变量 RAGENT_USERNAME / RAGENT_PASSWORD", file=sys.stderr)
        return 2

    print(f"\n登录 {base_url} ...")
    token = login(base_url, username, password)
    print("OK")

    print("\n⚠️  3 秒后开始删除，Ctrl-C 可取消 ...")
    try:
        for i in range(3, 0, -1):
            print(f"   {i}")
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n已取消。")
        return 130

    failed: list[tuple[str, str]] = []
    for code, _name, level_tag, node_id in plan:
        ok, err = delete_node(base_url, token, node_id)
        if ok:
            print(f"  ✓ [{level_tag}] {code}")
        else:
            print(f"  ✗ [{level_tag}] {code}  {err}", file=sys.stderr)
            failed.append((code, err))

    print(f"\n完成：成功 {len(plan) - len(failed)}，失败 {len(failed)}")

    if not args.keep_local and INTENT_IDS_PATH.exists():
        INTENT_IDS_PATH.unlink()
        rel = (
            INTENT_IDS_PATH.relative_to(PROJECT_ROOT)
            if INTENT_IDS_PATH.is_relative_to(PROJECT_ROOT)
            else INTENT_IDS_PATH
        )
        print(f"已删除本地：{rel}")

    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())

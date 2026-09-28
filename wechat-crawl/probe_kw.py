"""Probe: which keywords surface the most articles from the target account?"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from crawl_wechat import new_session, parse_results, sogou_search

ACCOUNT = "湖大有话说"
KEYWORDS = [
    "湖南大学万能墙",
    "HNU1022",
    "墙墙",
    "湖南大学",
    "湖大",
    "湖大 奖学金",
    "湖大 选课",
    "湖大 校历",
    "湖大 图书馆",
    "湖大 校园卡",
    "湖大 迎新",
    "湖大 考试",
    "湖大 放假",
    "湖大 保研",
    "湖大有话说 通知",
    "湖大 爆照",
    "湖南大学 通知",
    "湖大 宿舍",
    "湖大 开学",
    "湖南大学 报名",
]

PAGES = 3

s = new_session()
summary = []
for kw in KEYWORDS:
    titles = set()
    for page in range(1, PAGES + 1):
        rows = parse_results(sogou_search(s, kw, page), kw)
        for r in rows:
            if r["account"] == ACCOUNT:
                titles.add(r["title"])
        time.sleep(1.5)
    summary.append((kw, len(titles)))
    print(f"{kw:<16} -> {len(titles)}", flush=True)

print("\nranked:")
for kw, n in sorted(summary, key=lambda x: -x[1]):
    print(f"  {n:>3}  {kw}")

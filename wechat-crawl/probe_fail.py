"""Probe: why do some articles fail to fetch?"""

import re
import sys
import time

import requests
from bs4 import BeautifulSoup

sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from crawl_wechat import new_session, parse_results, resolve_real_url, sogou_search

kw = sys.argv[1]
account = sys.argv[2]

s = new_session()
for page in range(1, 4):
    html = sogou_search(s, kw, page)
    rows = [r for r in parse_results(html, kw) if r["account"] == account]
    if not rows:
        break
    for row in rows:
        real, why = resolve_real_url(s, row["sogou_link"])
        if not real:
            print("RESOLVE-FAIL", row["title"], "|", why)
            continue
        r = s.get(real, timeout=30, allow_redirects=True)
        r.encoding = "utf-8"
        soup = BeautifulSoup(r.text, "html.parser")
        content = soup.select_one("#js_content")
        print("-" * 60)
        print("title :", row["title"])
        print("url   :", r.url[:150])
        print("status:", r.status_code, "len:", len(r.text), "js_content:", bool(content))
        if not content:
            for marker in ["该内容已被发布者删除", "环境异常", "参数错误", "请在微信客户端", "已被删除", "违规"]:
                if marker in r.text:
                    print("  marker:", marker)
            print("  head:", " ".join(r.text[:300].split()))
        time.sleep(2)
    time.sleep(2)

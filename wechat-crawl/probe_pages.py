"""Probe: how deep can Sogou-WeChat article search go for one keyword?"""

import re
import time

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
SOGOU = "https://weixin.sogou.com"
KW = "湖大有话说"

s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
s.get(SOGOU, timeout=20)

seen: dict[str, str] = {}
for page in range(1, 6):
    r = s.get(
        f"{SOGOU}/weixin",
        params={"type": 2, "query": KW, "page": page, "ie": "utf8"},
        headers={"Referer": f"{SOGOU}/"},
        timeout=25,
    )
    r.encoding = "utf-8"
    soup = BeautifulSoup(r.text, "html.parser")
    items = soup.select("ul.news-list li")
    pages = soup.select("#pagebar_container a")
    print(
        f"page={page} len={len(r.text)} items={len(items)} "
        f"captcha={'验证码' in r.text} pager={len(pages)}"
    )
    for li in items:
        a = li.select_one("h3 a")
        acct = li.select_one("span.all-time-y2")
        if not a:
            continue
        t = a.get_text(" ", strip=True)
        seen[t] = acct.get_text(strip=True) if acct else "?"
    if not items:
        break
    time.sleep(2)

print("\nunique titles:", len(seen))
for t, a in list(seen.items()):
    print(f"  [{a}] {t[:60]}")

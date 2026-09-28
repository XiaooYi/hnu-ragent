"""Probe: find the Sogou account page (gzh?openid=...) for an account."""

import re

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
SOGOU = "https://weixin.sogou.com"

s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
s.get(SOGOU, timeout=20)

r = s.get(
    f"{SOGOU}/weixin",
    params={"type": 1, "query": "湖大有话说", "ie": "utf8"},
    headers={"Referer": f"{SOGOU}/"},
    timeout=25,
)
r.encoding = "utf-8"
html = r.text
print("len:", len(html))

soup = BeautifulSoup(html, "html.parser")
links = soup.select('a[href*="/gzh?openid="]')
print("gzh links:", len(links))
for a in links:
    print("  ", a.get("href"), "|", a.get_text(" ", strip=True)[:40])

if not links:
    openids = set(re.findall(r'openid=([A-Za-z0-9_\-]+)', html))
    print("raw openids:", list(openids)[:5])
    print("has captcha:", "验证码" in html or "antispider" in html)
    print("title:", soup.title.get_text(strip=True) if soup.title else None)
    for div in soup.select("ul.news-list2 > li")[:3]:
        print("  li:", " ".join(div.get_text(" ", strip=True).split())[:120])

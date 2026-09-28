"""Probe: fetch one mp.weixin.qq.com article and locate its __biz id."""

import re
import sys

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

URL = sys.argv[1]

s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
r = s.get(URL, timeout=25)
r.encoding = "utf-8"
html = r.text

print("status :", r.status_code)
print("length :", len(html))

soup = BeautifulSoup(html, "html.parser")

title = soup.select_one("#activity-name") or soup.select_one("h1")
acct = soup.select_one("#js_name") or soup.select_one(".rich_media_meta_text")
author = soup.select_one("#js_author_name")
publish = soup.select_one("#publish_time")

print("title  :", title.get_text(" ", strip=True) if title else None)
print("account:", acct.get_text(" ", strip=True) if acct else None)
print("author :", author.get_text(" ", strip=True) if author else None)
print("time   :", publish.get_text(" ", strip=True) if publish else None)

biz = re.search(r'__biz\s*[:=]\s*[\'"]([^\'"]+)', html) or re.search(
    r'var\s+biz\s*=\s*[\'"]([^\'"]+)', html
)
print("__biz  :", biz.group(1) if biz else None)

content = soup.select_one("#js_content")
if content:
    text = content.get_text("\n", strip=True)
    print("chars  :", len(text))
    print("head   :", text[:300].replace("\n", " | "))
    imgs = [i.get("data-src") or i.get("src") for i in content.find_all("img")]
    print("images :", len(imgs), imgs[:3])
else:
    print("NO #js_content — page likely blocked or requires client")
    print(html[:500].replace("\n", " "))

"""Probe: recover the account's __biz id and try the full-history endpoint."""

import re
import sys

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
WX_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/110.0 Mobile "
    "Safari/537.36 MMWEBID/1234 MicroMessenger/8.0.42.2481(0x28002A3D) "
    "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"
)

url = sys.argv[1]
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
html = s.get(url, timeout=25).text

print("== biz candidates ==")
for pat in [
    r"var\s+biz\s*=\s*[\"']([A-Za-z0-9+/=_-]{8,})[\"']",
    r"__biz[\"']?\s*[:=]\s*[\"']([A-Za-z0-9+/=_-]{8,})[\"']",
    r"biz\s*[:=]\s*[\"'](Mz[A-Za-z0-9+/=_-]{8,})[\"']",
]:
    for m in re.finditer(pat, html):
        print(" ", pat[:20], "->", m.group(1))

print("== Mz* literals (first 10 unique) ==")
seen = []
for m in re.finditer(r"(Mz[A-Za-z0-9+/=]{10,30})", html):
    if m.group(1) not in seen:
        seen.append(m.group(1))
print(" ", seen[:10])

print("== uin/key/pass_ticket hints ==")
for k in ["appmsg_token", "pass_ticket", "key=", "uin="]:
    print(f"  {k}: {html.count(k)}")

if seen:
    biz = seen[0]
    s2 = requests.Session()
    s2.headers.update({"User-Agent": WX_UA, "Accept-Language": "zh-CN,zh;q=0.9"})
    r = s2.get(
        "https://mp.weixin.qq.com/mp/profile_ext",
        params={
            "action": "home",
            "__biz": biz,
            "scene": "124",
            "is_ok": "1",
            "fasttmpl_type": "0",
            "fasttmpl_flag": "0",
            "devicetype": "Android",
        },
        timeout=25,
    )
    r.encoding = "utf-8"
    print("\n== profile_ext(history) ==")
    print("  status:", r.status_code, "len:", len(r.text))
    print("  has msgList:", "msgList" in r.text)
    print("  has 环境异常:", "环境异常" in r.text)
    print("  has 请在微信客户端:", "请在微信客户端" in r.text)
    snippet = r.text[:400].replace("\n", " ")
    print("  head:", snippet)

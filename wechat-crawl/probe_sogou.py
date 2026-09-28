"""Probe: resolve Sogou-WeChat search results into real mp.weixin.qq.com URLs."""

import re
import sys
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
SOGOU = "https://weixin.sogou.com"


def new_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
    s.get(SOGOU, timeout=20)
    return s


def search(s: requests.Session, keyword: str, page: int = 1) -> str:
    r = s.get(
        f"{SOGOU}/weixin",
        params={"type": 2, "query": keyword, "page": page, "ie": "utf8"},
        headers={"Referer": f"{SOGOU}/"},
        timeout=25,
    )
    r.encoding = "utf-8"
    return r.text


def parse(html: str) -> list[dict]:
    soup = BeautifulSoup(html, "html.parser")
    rows = []
    for li in soup.select("ul.news-list li"):
        a = li.select_one("h3 a")
        if not a:
            continue
        ts = re.search(r"timeConvert\('(\d+)'\)", str(li))
        acct = li.select_one("span.all-time-y2")
        rows.append(
            {
                "title": a.get_text(" ", strip=True),
                "link": a.get("href"),
                "account": acct.get_text(strip=True) if acct else "",
                "ts": ts.group(1) if ts else "",
            }
        )
    return rows


def resolve(s: requests.Session, link: str) -> str | None:
    if link.startswith("http"):
        return link
    r = s.get(
        urljoin(SOGOU + "/", link),
        headers={"Referer": f"{SOGOU}/"},
        timeout=25,
    )
    r.encoding = "utf-8"
    if "mp.weixin.qq.com" in r.url:
        return r.url
    chunks = re.findall(r"url\s*\+=\s*'([^']*)'", r.text)
    if chunks:
        return "".join(chunks).replace("@", "").replace("&amp;", "&")
    m = re.search(r"(https?://mp\.weixin\.qq\.com/s\?[^\"'<>\\]+)", r.text)
    return m.group(1).replace("&amp;", "&") if m else None


def main() -> None:
    kw = sys.argv[1] if len(sys.argv) > 1 else "湖大有话说"
    s = new_session()
    html = search(s, kw)
    rows = parse(html)
    print(f"keyword={kw} results={len(rows)}")
    for row in rows[:3]:
        url = resolve(s, row["link"])
        print("-" * 70)
        print("title  :", row["title"])
        print("account:", row["account"])
        print("ts     :", row["ts"])
        print("real   :", url)


if __name__ == "__main__":
    main()

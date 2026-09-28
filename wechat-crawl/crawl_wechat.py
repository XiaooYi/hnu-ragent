"""Crawl WeChat official-account articles via Sogou-WeChat search.

Usage:
    python crawl_wechat.py --account "湖大有话说" --out out \
        --keyword "湖大有话说" --keyword "湖南大学考试学习资料汇总" \
        --download-images

Outputs one Markdown file per article, index.jsonl (metadata) and
failed.jsonl (articles that could not be fetched, with the reason).
The crawler is resumable: entries already in index.jsonl are skipped.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import time
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

SOGOU = "https://weixin.sogou.com"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

BLOCK_TAGS = {
    "p", "section", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5",
    "h6", "blockquote", "tr", "table", "pre", "figure", "figcaption",
    "article", "header", "footer", "hr",
}


def log(msg: str) -> None:
    print(msg, flush=True)


def new_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"})
    s.get(SOGOU, timeout=20)
    return s


# --------------------------------------------------------------------------- #
# search
# --------------------------------------------------------------------------- #
def sogou_search(s: requests.Session, keyword: str, page: int) -> str:
    last: Exception | None = None
    for attempt in range(4):
        try:
            r = s.get(
                f"{SOGOU}/weixin",
                params={"type": 2, "query": keyword, "page": page, "ie": "utf8"},
                headers={"Referer": f"{SOGOU}/"},
                timeout=25,
            )
            r.encoding = "utf-8"
            if "验证码" in r.text or "antispider" in r.text:
                log(f"  ! captcha on page {page}, backing off")
                time.sleep(20 + 10 * attempt)
                continue
            return r.text
        except requests.RequestException as exc:
            last = exc
            time.sleep(3 + 3 * attempt)
    raise RuntimeError(f"search failed for {keyword} page {page}: {last}")


def parse_results(html: str, keyword: str) -> list[dict]:
    soup = BeautifulSoup(html, "html.parser")
    rows = []
    for li in soup.select("ul.news-list li"):
        a = li.select_one("h3 a")
        if not a:
            continue
        acct = li.select_one("span.all-time-y2")
        ts = re.search(r"timeConvert\('(\d+)'\)", str(li))
        rows.append(
            {
                "title": a.get_text(" ", strip=True),
                "sogou_link": urljoin(SOGOU + "/", a.get("href") or ""),
                "account": acct.get_text(strip=True) if acct else "",
                "ts": int(ts.group(1)) if ts else 0,
                "keyword": keyword,
            }
        )
    return rows


def rearm_session(s: requests.Session, wait: float = 0.0) -> None:
    """Drop anti-spider state and warm the session up again."""
    if wait:
        time.sleep(wait)
    s.cookies.clear()
    try:
        s.get(SOGOU, timeout=20)
    except requests.RequestException:
        pass


def resolve_real_url(
    s: requests.Session, sogou_link: str, attempts: int = 3
) -> tuple[str | None, str]:
    last = "unknown"
    for attempt in range(attempts):
        try:
            r = s.get(sogou_link, headers={"Referer": f"{SOGOU}/"}, timeout=25)
            r.encoding = "utf-8"
            if "mp.weixin.qq.com" in r.url:
                return r.url, ""
            chunks = re.findall(r"url\s*\+=\s*'([^']*)'", r.text)
            if chunks:
                return "".join(chunks).replace("@", "").replace("&amp;", "&"), ""
            m = re.search(
                r"(https?://mp\.weixin\.qq\.com/s\?[^\"'<>\\]+)", r.text
            )
            if m:
                return m.group(1).replace("&amp;", "&"), ""
            last = "captcha/antispider" if "验证码" in r.text else f"http {r.status_code}"
        except requests.RequestException as exc:
            last = f"network: {exc}"
        rearm_session(s, wait=5 + 5 * attempt)
    return None, last


# --------------------------------------------------------------------------- #
# article
# --------------------------------------------------------------------------- #
def html_to_text(node) -> str:
    parts: list[str] = []

    def walk(el) -> None:
        name = getattr(el, "name", None)
        if name == "br":
            parts.append("\n")
            return
        if name == "img":
            parts.append("[图片]")
            return
        if name in ("script", "style"):
            return
        is_block = name in BLOCK_TAGS
        if is_block:
            parts.append("\n")
        for child in el.children:
            if getattr(child, "name", None):
                walk(child)
            else:
                parts.append(str(child))
        if is_block:
            parts.append("\n")

    walk(node)
    text = "".join(parts)
    text = text.replace("\u00a0", " ").replace("\u200b", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n[ \t]+", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def meta_value(html: str, patterns: list[str]) -> str:
    for pat in patterns:
        m = re.search(pat, html, re.S)
        if m:
            return BeautifulSoup(m.group(1), "html.parser").get_text(" ", strip=True)
    return ""


META_PATTERNS = {
    "title": [r"var\s+msg_title\s*=\s*'([^']*)'", r"document\.title\s*=\s*'([^']*)'"],
    "account": [r'var\s+nickname\s*=\s*"([^"]*)"', r'id="js_name"[^>]*>(.*?)</a>'],
    "author": [r'var\s+author\s*=\s*"([^"]*)"'],
    "ct": [r'var\s+ct\s*=\s*"(\d+)"', r'var\s+create_time\s*=\s*"(\d+)"'],
    "link": [r'var\s+msg_link\s*=\s*"(.*?)"', r'shareData\.link\s*=\s*"(.*?)"'],
}


def clean_msg_link(raw: str) -> str:
    url = raw.replace("&amp;", "&").replace("http://", "https://")
    url = re.sub(r"#wechat_redirect$", "", url)
    keep = ("__biz", "mid", "idx", "sn")
    m = re.search(r"mp\.weixin\.qq\.com/s\?(.*)$", url)
    if not m:
        return url
    params = {}
    for chunk in m.group(1).split("&"):
        if "=" in chunk:
            k, v = chunk.split("=", 1)
            if k in keep and k not in params:
                params[k] = v
    if all(params.get(k) for k in keep):
        return "https://mp.weixin.qq.com/s?" + "&".join(
            f"{k}={params[k]}" for k in keep
        )
    return url


def fetch_article(s: requests.Session, url: str) -> tuple[dict | None, str]:
    r = None
    for attempt in range(3):
        try:
            r = s.get(url, timeout=30)
            r.encoding = "utf-8"
            break
        except requests.RequestException as exc:
            last = exc
            time.sleep(3 + attempt * 2)
    if r is None:
        return None, f"network: {last}"

    html = r.text
    soup = BeautifulSoup(html, "html.parser")
    content = soup.select_one("#js_content")
    if not content:
        for marker in (
            "该内容已被发布者删除",
            "该内容已被发布者删除或违规",
            "此内容因违规无法查看",
            "环境异常",
            "参数错误",
            "请在微信客户端打开链接",
        ):
            if marker in html:
                return None, f"blocked: {marker}"
        return None, "no #js_content"

    meta = {k: meta_value(html, v) for k, v in META_PATTERNS.items()}
    meta["link"] = clean_msg_link(meta["link"]) if meta["link"] else ""

    images = []
    for img in content.find_all("img"):
        src = img.get("data-src") or img.get("src")
        if src and src not in images:
            images.append(src)

    return {"meta": meta, "text": html_to_text(content), "images": images}, ""


def download_images(
    s: requests.Session, urls: list[str], folder: Path, slug: str
) -> list[str]:
    folder.mkdir(parents=True, exist_ok=True)
    local = []
    for i, url in enumerate(urls, 1):
        ext = "jpg"
        m = re.search(r"wx_fmt=(\w+)", url)
        if m:
            ext = "png" if m.group(1) == "png" else m.group(1)
        name = f"{slug}_{i:02d}.{ext}"
        target = folder / name
        if not target.exists():
            try:
                r = s.get(url, timeout=30, headers={"Referer": "https://mp.weixin.qq.com/"})
                if r.status_code == 200 and r.content:
                    target.write_bytes(r.content)
                    time.sleep(0.2 + random.random() * 0.3)
                else:
                    continue
            except requests.RequestException:
                continue
        local.append(name)
    return local


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def safe_name(title: str, limit: int = 60) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", title).strip(" ._")
    name = re.sub(r"\s+", " ", name)
    return (name[:limit] or "untitled").strip()


def norm_title(title: str) -> str:
    return re.sub(r"[\s!！,.。~、|｜:：;；'\"“”‘’()（）\[\]【】]+", "", title)


def load_index(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            out[row["key"]] = row
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--account", required=True)
    ap.add_argument("--keyword", action="append", default=[])
    ap.add_argument("--max-pages", type=int, default=10)
    ap.add_argument("--out", default="out")
    ap.add_argument("--delay", type=float, default=2.5)
    ap.add_argument(
        "--resolve-delay",
        type=float,
        default=8.0,
        help="seconds to wait between Sogou redirect resolutions (rate limited)",
    )
    ap.add_argument("--download-images", action="store_true")
    args = ap.parse_args()

    keywords = args.keyword or [args.account]
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)
    index_path = outdir / "index.jsonl"
    failed_path = outdir / "failed.jsonl"
    index = load_index(index_path)
    s = new_session()

    found: dict[str, dict] = {}
    for kw in keywords:
        empty_streak = 0
        for page in range(1, args.max_pages + 1):
            html = sogou_search(s, kw, page)
            rows = parse_results(html, kw)
            keep = [r for r in rows if r["account"] == args.account]
            log(f"[search] kw={kw} page={page} hits={len(rows)} matched={len(keep)}")
            for r in keep:
                found.setdefault(norm_title(r["title"]), r)
            if not rows:
                break
            empty_streak = empty_streak + 1 if not keep else 0
            if empty_streak >= 2:
                break
            time.sleep(args.delay + random.random())

    log(f"[search] unique matched articles: {len(found)}")
    ordered = sorted(found.values(), key=lambda r: r["ts"], reverse=True)

    ok = skipped = failed = 0
    resolve_streak = 0
    cooldown_rounds = 0
    with index_path.open("a", encoding="utf-8") as idx, failed_path.open(
        "a", encoding="utf-8"
    ) as failfh:
        for n, row in enumerate(ordered, 1):
            key = norm_title(row["title"])
            if key in index:
                skipped += 1
                continue

            real, why = resolve_real_url(s, row["sogou_link"])
            time.sleep(args.resolve_delay + random.random() * 2)
            if not real:
                failed += 1
                resolve_streak += 1
                failfh.write(
                    json.dumps(
                        {
                            "title": row["title"],
                            "sogou_link": row["sogou_link"],
                            "reason": f"resolve failed: {why}",
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                failfh.flush()
                log(f"[{n}/{len(ordered)}] RESOLVE FAIL ({why}) {row['title'][:40]}")
                if resolve_streak >= 3:
                    wait = min(60 * (2**cooldown_rounds), 300)
                    cooldown_rounds += 1
                    log(f"  ! 3 resolve failures in a row — cooldown {wait:.0f}s")
                    rearm_session(s, wait=wait)
                    resolve_streak = 0
                continue
            resolve_streak = 0
            cooldown_rounds = 0

            art, reason = fetch_article(s, real)
            time.sleep(args.delay + random.random())
            if not art:
                failed += 1
                failfh.write(
                    json.dumps(
                        {"title": row["title"], "url": real, "reason": reason},
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                failfh.flush()
                log(f"[{n}/{len(ordered)}] FAIL ({reason}) {row['title'][:40]}")
                continue

            meta = art["meta"]
            ct = int(meta["ct"]) if meta["ct"].isdigit() else row["ts"]
            date = datetime.fromtimestamp(ct).strftime("%Y-%m-%d")
            title = meta["title"] or row["title"]
            canonical = meta["link"] or real
            slug = hashlib.md5(f"{date}{title}".encode()).hexdigest()[:8]
            fname = f"{date}_{safe_name(title)}.md"

            body = [f"# {title}", ""]
            body.append(f"- 公众号：{meta['account'] or args.account}")
            if meta["author"]:
                body.append(f"- 作者：{meta['author']}")
            body.append(f"- 日期：{date}")
            body.append(f"- 原文：{canonical}")
            body.append("")
            body.append(art["text"])

            if art["images"]:
                if args.download_images:
                    local = download_images(s, art["images"], outdir / "images", slug)
                    body.append("")
                    body.append("## 图片")
                    body.extend(f"![](images/{name})" for name in local)
                else:
                    body.append("")
                    body.append("## 图片")
                    body.extend(f"![]({u})" for u in art["images"])

            (outdir / fname).write_text("\n".join(body) + "\n", encoding="utf-8")

            rec = {
                "key": key,
                "title": title,
                "account": meta["account"] or args.account,
                "author": meta["author"],
                "date": date,
                "ts": ct,
                "url": canonical,
                "file": fname,
                "chars": len(art["text"]),
                "images": len(art["images"]),
                "keywords": [row["keyword"]],
            }
            idx.write(json.dumps(rec, ensure_ascii=False) + "\n")
            idx.flush()
            index[key] = rec
            ok += 1
            log(f"[{n}/{len(ordered)}] OK {date} {rec['chars']}字 {title[:40]}")

    log(f"[done] saved={ok} skipped={skipped} failed={failed} -> {outdir.resolve()}")


if __name__ == "__main__":
    main()

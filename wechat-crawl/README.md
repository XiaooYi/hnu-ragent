# 微信公众号文章爬取（湖大有话说）

通过**搜狗微信文章搜索**（`weixin.sogou.com/weixin?type=2`）检索 → 过滤出目标公众号
→ 还原真实 `mp.weixin.qq.com` 链接 → 抓正文与图片 → 存成 Markdown。

## 用法

```bash
python crawl_wechat.py \
  --account "湖大有话说" \
  --out out \
  --max-pages 10 \
  --download-images \
  --keyword "HNU1022" \
  --keyword "湖大有话说"
```

- `--account` 结果里必须完全等于该名称才会被收录（搜狗结果会混入其它号）。
- `--keyword` 可重复；同一篇文章按标题去重。
- `--download-images` 把图片存到 `out/images/`，Markdown 里改成本地相对路径；
  不加则写入 `mmbiz.qpic.cn` 原始链接（有防盗链，多数场景打不开）。
- 断点续抓：已在 `out/index.jsonl` 里的文章会跳过，可以反复追加关键词运行。

## 产出

| 文件 | 内容 |
|------|------|
| `out/YYYY-MM-DD_标题.md` | 单篇文章：标题、公众号、作者、日期、原文链接、正文、图片 |
| `out/index.jsonl` | 元数据索引（标题/日期/字数/图片数/链接） |
| `out/failed.jsonl` | 抓取失败的条目及原因 |
| `out/images/` | 下载下来的图片 |

## 已知限制

1. **覆盖不完整**：搜狗只收录部分文章，翻页上限约 10 页/关键词，
   所以本方案拿到的是"搜狗已收录"的部分，不是该号完整历史。
2. **原文链接带签名**：搜狗还原出的是 `?src=11&timestamp=...&signature=...`
   形式的临时链接，会过期。正文页里 `var sn` 为空，拿不到
   `__biz/mid/idx/sn` 永久链接。**本地归档才是长期可用的产物。**
3. **已删除文章抓不到**：例如《湖南大学考试学习资料汇总》系列，
   微信侧返回"该内容已被发布者删除"。
4. 搜狗可能弹验证码，脚本会退避重试；频繁运行容易被限。

## 想要完整历史？

需要带登录态，二选一：

- **微信公众平台**：登录 `mp.weixin.qq.com`（个人可免费注册订阅号），
  提供 cookie + token，用 `cgi-bin/searchbiz` 拿 `fakeid`，
  再用 `cgi-bin/appmsg?action=list_ex` 分页枚举全部文章。
- **微信客户端抓包**：从手机端请求里取 `key` / `appmsg_token` /
  `pass_ticket` / `uin`，调用 `mp/profile_ext?action=getmsg` 分页拉历史。

## 辅助脚本

`probe_*.py` 是调试用的小工具（验证搜索、还原链接、定位 `__biz`、
统计关键词命中率），正式抓取只需要 `crawl_wechat.py`。

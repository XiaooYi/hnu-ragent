# 湖南大学校内信息评测

## 数据与服务器知识库

`eval/rag/dataset/eval_set_hnu_v1.jsonl` 是独立于比特严选评估集的湖大校内信息集，当前包含 30 条有标准答案和相关文档的样本。题目依据 CVM 当前五个知识库中的已分块内容整理。

| collection | 知识库 | 文档数 | chunk 数 |
|---|---|---:|---:|
| `hnu1general1campus` | 湖大通用概况与校园生活 | 24 | 108 |
| `hnu3undergrad3academic` | 湖大本科教学与学业制度 | 45 | 459 |
| `hnu1undergrad1curriculum` | 湖南大学本科专业培养方案 | 86 | 3,398 |
| `hnu1scholarship1aid` | 湖大奖助学金资助 | 13 | 74 |
| `hnu1grad1management` | 湖大研究生新生与研究生管理 | 21 | 324 |

服务器 API 在 `/rag/eval` 中返回以文档文件名去掉扩展名后的业务 ID；评估集的 `expected_doc_ids` 按此格式填写。例如 `人工智能.pdf` 对应 `人工智能`。相同文件名对应的 ID 也会相同，这是当前 RAGent 接口的 ID 语义。

## 湖大评估集使用的意图叶子编码

评估集的 `intent_l2` 与 RAGent `/rag/eval` 返回的意图编码直接对比，不能改成中文展示名。

| 编码 | 服务器意图名称 |
|---|---|
| `hnu-general-life-overview` | 学校概况与校园文化 |
| `hnu-general-life-campus-card-network` | 校园卡与校园网 |
| `hnu-general-life-student-rules-workstudy` | 学生管理与勤工助学 |
| `hnu-undergraduate-rules-major-change-minor` | 转专业、专业分流与辅修 |
| `hnu-undergraduate-rules-course-selection` | 选课与课堂教学 |
| `hnu-undergraduate-rules-admission-special` | 招生与特殊学生培养 |
| `hnu-undergraduate-rules-student-status-degree` | 学籍与学位 |
| `hnu-undergraduate-programs-program-query` | 本科专业培养方案查询 |
| `hnu-undergraduate-funding-grants-hardship` | 助学金与困难认定 |
| `hnu-undergraduate-funding-scholarships` | 奖学金与荣誉奖励 |
| `hnu-graduate-management-practice-management` | 研究生校外修课与专业实践 |
| `hnu-graduate-management-status-degree` | 研究生学籍、学位与中期考核 |

## 运行

配置 `RAGENT_BASE_URL`、`RAGENT_USERNAME`、`RAGENT_PASSWORD`，确认服务端启用
`app.eval.enabled` 且评测机可以访问两个 RAGent 接口。三个变量写进项目根目录 `.env`
最省事（`cp .env.example .env` 后填值，环境变量优先级高于 `.env`）。

CVM 上 RAGent 后端跑在容器里、由 nginx 暴露在 80 端口，宿主机连不上 9090，
所以这台机器上 `RAGENT_BASE_URL` 要用 `http://localhost/api/ragent`。

```bash
python3 -m eval rag run --dataset eval/rag/dataset/eval_set_hnu_v1.jsonl --limit 5
python3 -m eval rag score eval/runs/hnu_v1_YYYYMMDD_HHMMSS.jsonl --skip-ragas
python3 -m eval rag report eval/runs/hnu_v1_YYYYMMDD_HHMMSS.jsonl
```

商品数据继续使用 `eval/rag/dataset/doc_id_map.json`。湖大数据直接使用文件名业务 ID，不依赖该商品上传映射，也不运行商品知识库创建、上传、清理或意图树构建脚本。

这 30 条样本用于首轮基线与问题定位。比特严选的目标阈值尚未针对湖大语料校准，不应直接当作湖大评测的通过标准；标准答案覆盖范围也应随着新增问题和文档复核持续扩充。

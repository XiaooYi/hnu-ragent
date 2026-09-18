# RAG 高频问题整理与答案

> 说明：飞书链接当前需要登录，无法直接读取正文。因此本文先基于公开资料整理 RAG 高频问题，主体问题来自小林面试笔记公开的 RAG 20 题目录，并结合 RAG 原论文、HyDE、RAGAS、pgvector、GraphRAG、Self-RAG、CRAG 等资料补充答案。

## 参考资料

- [小林面试笔记：RAG 面试题介绍](https://xiaolinnote.com/ai/rag/rag_info.html)
- [小林面试笔记：Query Rewrite](https://xiaolinnote.com/ai/rag/12_query_rewrite.html)
- [Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks](https://arxiv.org/abs/2005.11401)
- [HyDE: Precise Zero-Shot Dense Retrieval without Relevance Labels](https://arxiv.org/abs/2212.10496)
- [RAGAS Metrics](https://docs.ragas.io/en/v0.1.21/concepts/metrics/)
- [pgvector](https://github.com/pgvector/pgvector)
- [LangChain Text Splitters](https://docs.langchain.com/oss/javascript/integrations/splitters)
- [LlamaIndex Chunk Size](https://developers.llamaindex.ai/python/framework/optimizing/basic_strategies/basic_strategies/)
- [Elastic Hybrid Search](https://www.elastic.co/what-is/hybrid-search)
- [Self-RAG](https://arxiv.org/abs/2310.11511)
- [Corrective RAG](https://arxiv.org/abs/2401.15884)
- [Microsoft GraphRAG](https://microsoft.github.io/graphrag/)
- [MTEB Embedding Benchmark](https://arxiv.org/abs/2210.07316)

## 1. 什么是 RAG？完整 RAG 系统的工作流程是什么？

RAG 是 Retrieval-Augmented Generation，检索增强生成。它的核心思想是：不要只依赖大模型参数里的静态知识，而是在回答前先从外部知识库检索相关资料，再把检索结果作为上下文交给大模型生成答案。

完整链路分两条：

```text
离线链路：文档采集 -> 解析 -> 清洗 -> 切块 -> embedding -> 向量库/索引库
在线链路：用户问题 -> query rewrite -> embedding -> 检索 -> rerank -> prompt 组装 -> LLM 生成 -> 引用/追踪
```

项目里可以结合 Ragent 这样讲：

```text
用户请求
  -> 加载会话记忆
  -> 问题重写和子问题拆分
  -> 意图识别
  -> 低置信度澄清
  -> 多路召回
  -> 去重、rerank、TopK 截断
  -> 拼接上下文
  -> 流式生成回答
```

RAG 不是“向量库 + 大模型”这么简单，真正难点在于检索质量、上下文控制、评估闭环和线上可观测性。

## 2. RAG 主要解决什么问题？

RAG 主要解决四类问题：

- 知识时效性：大模型训练后知识固定，RAG 可以接入最新文档。
- 私有知识：企业内部制度、产品手册、工单、FAQ 不在通用模型训练语料里。
- 可追溯性：回答可以引用来源，便于审核。
- 降低幻觉：模型回答被约束在检索到的上下文里，而不是纯靠参数生成。

面试时可以说：RAG 的目标不是让模型“更聪明”，而是让模型“有资料可查、有证据可依、有边界可控”。

## 3. 相比直接微调 LLM，RAG 解决了什么问题？两者优劣是什么？

RAG 和微调解决的问题不同。

RAG 适合解决知识注入问题，例如企业文档问答、产品政策查询、售后规则查询。它的优势是更新快、成本低、可追溯；劣势是依赖检索质量，召回错了就可能答错。

微调适合解决模型行为和能力风格问题，例如让模型学会固定输出格式、行业话术、分类能力、特定任务推理模式。它的优势是推理时不一定依赖外部检索；劣势是训练成本高、知识更新慢、难以追溯来源。

可以总结成：

```text
知识经常变、需要引用来源 -> RAG
行为模式稳定、需要模型学会某类能力 -> 微调
企业知识问答 -> 优先 RAG，必要时结合轻量微调
```

## 4. RAG 中的文档怎么存？粒度多大？文档切割策略怎么设计？

文档通常不是整篇直接存向量库，而是拆成 chunk。每个 chunk 存：

- chunk 文本
- embedding 向量
- doc_id、chunk_id
- 标题、层级、来源、更新时间
- collection 或知识库标识
- 权限、业务域、标签等 metadata

切块策略要看文档类型：

- FAQ：一问一答可以作为天然 chunk。
- Markdown/手册：按标题层级切，保留标题路径。
- 表格：按行、按产品、按参数组切，必要时转成结构化文本。
- 长 PDF：先按段落和标题切，再控制 token 长度。

chunk size 没有绝对值，常见做法是 300-800 tokens 或 500-1200 中文字符起步，overlap 控制在 10%-20%。小 chunk 精准但容易丢上下文，大 chunk 信息完整但召回噪声更高。

项目回答可以说：我会用评测集测试不同 `chunk_size` 和 `overlap`，比较 Recall@K、MRR、context precision 和最终回答准确率，而不是凭感觉调。

## 5. 怎么规避语义被切割掉的问题？

语义被切断通常发生在标题、定义、条件、结论分散在相邻 chunk 中。解决方式：

- 按语义结构切，不只按固定长度切。
- chunk 中保留标题路径，例如“售后政策 > 退货规则 > 手机类”。
- 使用 overlap，让相邻块共享一部分上下文。
- 父子 chunk：小 chunk 用于召回，大 parent chunk 用于生成。
- 相邻 chunk 合并：同一文档连续 chunk 被召回时合并。
- 对表格和参数类内容做专门解析，避免把一行参数切断。

面试里可以强调：切块不是越小越好，目标是“召回粒度小，生成上下文完整”。

## 6. Embedding 是什么？如何选择和评估 embedding 模型？

Embedding 是把文本映射成向量，使语义相近的文本在向量空间里距离更近。RAG 中文档和用户 query 都会被转成 embedding，再做相似度检索。

选型看几类指标：

- 语言：中文、英文还是多语言。
- 领域：通用、电商、代码、法律、医疗。
- 维度和成本：维度越高不一定越好，也会影响存储和检索性能。
- 检索效果：Recall@K、MRR、nDCG。
- 延迟和吞吐：是否能支撑在线并发。
- 稳定性：模型升级是否影响历史向量。

公开榜单可以参考 MTEB，但企业项目一定要用自己的业务评测集复测。因为通用榜单好，不代表在自己的产品文档、售后政策、内部制度上最好。

## 7. Embedding 有哪些算法？

可以按发展阶段回答：

- 静态词向量：Word2Vec、GloVe，词级向量，不理解上下文。
- 句向量模型：Sentence-BERT，把句子编码成语义向量。
- 双塔检索模型：query encoder 和 document encoder 分别编码，适合大规模召回。
- 稠密向量模型：BGE、E5、text-embedding、Qwen embedding 等。
- 稀疏向量模型：BM25、SPLADE，擅长关键词和专有名词。
- 多模态 embedding：文本、图片、音频统一映射。

RAG 工程里最常用的是 dense embedding + BM25/关键词的混合检索，再加 cross-encoder rerank。

## 8. 什么是向量数据库？有没有做过选型？

向量数据库用于存储 embedding 并支持近似最近邻搜索。常见能力包括：

- 向量写入和更新
- 相似度检索
- metadata 过滤
- ANN 索引，例如 HNSW、IVFFlat
- 多 collection 管理
- 水平扩展和权限控制

选型可以比较：

- pgvector：和 PostgreSQL 生态结合好，适合中小规模、业务表和向量共存。
- Milvus：专门的向量数据库，适合更大规模和高吞吐。
- Elasticsearch/OpenSearch：适合关键词、混合检索、日志搜索场景。
- Qdrant/Weaviate：向量检索能力完善，metadata 过滤体验好。

项目里用 pgvector 可以这样答：因为业务数据本身就在 PostgreSQL，文档、chunk、metadata、trace 都能统一管理，开发和运维成本低；如果数据量和 QPS 上来，再考虑 Milvus 或专用向量库。

## 9. 讲讲你用的向量数据库，数据量级、性能和瓶颈？

以 pgvector 为例，它在 PostgreSQL 中提供 vector 类型和相似度操作符，支持 HNSW、IVFFlat 等索引。项目里可以按这个结构回答：

- 存储：`t_knowledge_vector` 存 chunk 文本、embedding、metadata。
- 检索：query embedding 后按 cosine distance 或 inner product 排序。
- 过滤：通过 collection、知识库、权限等 metadata 缩小范围。
- 索引：向量字段建 HNSW，过滤字段建普通索引或表达式索引。

性能瓶颈主要有：

- JSONB metadata 过滤可能影响执行计划。
- collection 过多时，单表向量索引候选空间变大。
- embedding 维度高会增加存储和计算成本。
- HNSW 构建慢、占内存，需要调 `ef_search`、`m` 等参数。

面试最好补一句：我不会只说用了 HNSW，而是会用 `EXPLAIN ANALYZE` 看执行计划，确认过滤和向量索引是否按预期工作。

## 10. 用户输入进入 RAG 系统后，完整在线流程是什么？

在线流程可以这样讲：

```text
1. 接收用户 query
2. 加载会话历史和摘要记忆
3. 术语归一化
4. Query Rewrite，补全指代和上下文
5. 复杂问题拆成多个子问题
6. 意图识别，决定查哪个知识库或是否调用工具
7. 多路召回：意图定向检索 + 全局向量检索 + 可选关键词检索
8. 合并、去重、rerank
9. TopK 和 token budget 控制
10. 组装 Prompt
11. LLM 流式生成
12. 记录 trace、指标、日志
```

面试官如果追问顺序，核心逻辑是：先理解问题，再决定查哪里，再带证据回答。

## 11. 向量检索和关键词检索有什么区别？

关键词检索，例如 BM25，擅长精确匹配。优点是可解释、对型号、编号、专有名词、错误码很强；缺点是不理解同义表达。

向量检索擅长语义匹配。优点是能处理口语化、同义词、语义相近的问题；缺点是对精确数字、型号、缩写可能不稳定。

所以工程里常用混合检索：

```text
BM25/关键词：保证精确词命中
向量检索：保证语义召回
RRF/加权融合：合并排序
rerank：最终精排
```

例如“小米 14 Ultra 保修期”这种问题，关键词检索能抓住型号；“手机摔坏了还能免费修吗”这种问题，向量检索更容易命中售后政策。

## 12. Query Rewrite 的目的是什么？有哪些方法？

Query Rewrite 的目的，是弥合用户表达和知识库文档表达之间的语义鸿沟。

常见方法：

- 直接改写：把口语、缩写、指代改成正式检索表达。
- Query 扩展：补充同义词、相关关键词、产品别名。
- HyDE：先让 LLM 生成假设答案，再用假设答案向量检索。
- Step-back Prompting：把具体问题抽象成背景问题。
- 多 Query：生成多个角度的问题并行检索。

如何区分：

- “这个怎么退”这种口语/指代问题，用直接改写。
- “退款政策是什么”但文档是陈述型政策文本，用 HyDE。
- “HNSW 的 ef_search 设 100 合适吗”这种需要原理支撑的问题，用 Step-back。
- “小米 14 和 15 从拍照、续航、屏幕怎么选”这种多维度问题，用多 Query 或子问题拆分。

项目里可以说：当前核心实现是术语归一化 + 直接改写 + 子问题拆分；HyDE、Step-back、多 Query 可以作为后续增强，不要说成全部已落地。

## 13. 什么是多路召回？具体怎么做？

多路召回就是从多个检索通道拿候选结果，再统一合并、去重、重排。它解决的是单一检索方式召回不稳定的问题。

常见通道：

- 意图定向检索：根据意图查指定知识库。
- 全局向量检索：避免意图识别错导致漏召回。
- 关键词/BM25：补型号、编号、专有名词。
- FAQ 精确匹配：适合标准问答。
- 工具/MCP 查询：查实时业务系统。

典型流程：

```text
query -> 多通道并行召回 -> 结果归一化 -> 去重 -> 融合排序 -> rerank -> TopK
```

Ragent 项目里可以重点讲“意图定向检索 + 全局向量检索”：前者提高精准度，后者做兜底覆盖；低置信意图时双路都走。

## 14. RAG 检索优化策略有哪些？

可以从检索前、检索中、检索后三层回答。

检索前：

- 文档清洗
- 语义切块
- 标题路径补全
- 术语归一化
- Query Rewrite
- 子问题拆分

检索中：

- embedding 模型优化
- 向量索引调参
- metadata 过滤
- 混合检索
- 多路召回
- 权限过滤

检索后：

- 去重
- rerank
- MMR 多样性
- 相邻 chunk 合并
- TopK 控制
- token budget 控制

最后一定要落到评测：优化不能只凭感觉，要看 Recall@K、MRR、context precision、faithfulness、answer correctness 和延迟。

## 15. 有哪些更复杂的 RAG 范式？

常见进阶范式：

- HyDE：用假设答案做检索代理。
- Self-RAG：模型判断是否需要检索，并对检索结果和生成内容自我反思。
- CRAG：先评估检索质量，如果检索结果差，就触发纠错或外部搜索。
- GraphRAG：把文档抽取成知识图谱，适合全局总结和关系推理。
- Agentic RAG：由 Agent 决定查知识库、查工具、拆任务、多轮检索。
- Parent-Child RAG：小 chunk 召回，大 chunk 生成。

面试时不要堆名词，要说适用场景：

- 普通知识问答：基础 RAG + rerank。
- 多跳关系：GraphRAG。
- 检索不稳定：CRAG。
- 复杂任务流：Agentic RAG。
- 长文档摘要：GraphRAG 或层级摘要。

## 16. 什么场景会用图数据库增强向量检索？

当问题依赖实体关系、层级结构、全局主题时，图数据库比纯向量检索更合适。

典型场景：

- 企业组织架构、权限关系。
- 商品、配件、兼容性关系。
- 故障原因、症状、解决方案链路。
- 法律条款、制度上下位关系。
- 文档全集的主题总结。

纯向量检索擅长找“相似段落”，但不擅长回答“某实体和多个实体之间的关系”。GraphRAG 会先从文档抽取实体和关系，构建社区或图谱，再结合图检索和文本检索回答。

缺点也要说：图谱构建成本高，实体抽取可能错，维护复杂，不是所有 RAG 都需要图数据库。

## 17. 如何规避 RAG 系统中的幻觉？

RAG 幻觉来源可能在三个地方：

- 检索错：召回了无关上下文。
- 上下文污染：相关和不相关内容混在一起。
- 生成错：模型没有严格依据上下文。

规避方式：

- 提升召回质量：query rewrite、多路召回、rerank。
- 控制上下文：TopK、去重、token budget、只保留高分 chunk。
- Prompt 约束：要求只基于上下文回答，不知道就说不知道。
- 引用来源：回答中标注 doc/chunk 来源。
- 置信度判断：低召回分数时不强答，触发澄清或拒答。
- 评测监控：用 faithfulness、context precision、人工抽检监控。

面试里可以说：RAG 只能降低幻觉，不能天然消灭幻觉。检索错了，模型仍然可能一本正经地答错。

## 18. 怎么量化 RAG 效果？

要拆成组件评估，而不是只看最终回答。

检索指标：

- Recall@K：标准答案相关 chunk 是否被召回。
- Precision@K：召回结果有多少是相关的。
- MRR：第一个正确结果排得多靠前。
- nDCG：考虑排序质量。

生成指标：

- Faithfulness：回答是否被上下文支持。
- Answer Relevancy：回答是否切题。
- Answer Correctness：答案是否正确。
- Context Precision / Recall：上下文质量。

线上指标：

- 平均延迟、P95、P99。
- 检索为空率。
- 澄清率。
- 用户追问率。
- 点赞/点踩。
- 人工质检通过率。

RAGAS 这类工具可以辅助自动评测，但关键样本仍建议人工标注，尤其是企业内部知识问答。

## 19. RAG 知识库如何动态和持续更新？

知识库更新要保证“文档、chunk、向量、索引、权限”一致。

流程：

```text
文档变更监听
  -> 解析文档
  -> 对比版本
  -> 增量切块
  -> 重新 embedding
  -> 更新向量库
  -> 删除旧 chunk 或标记失效
  -> 更新索引和缓存
```

关键点：

- 文档要有版本号和更新时间。
- chunk 要能追溯到 doc_id 和版本。
- 删除文档时不能只删业务表，还要删向量。
- embedding 模型变更时必须重建索引，不能新旧向量混用。
- 权限变更要影响检索过滤。
- 更新后要跑回归评测，避免新文档污染旧问答。

面试可以补一句：知识库不是一次性导入，生产 RAG 的难点是持续更新和一致性。

## 20. RAG 实际落地最难的地方是什么？

我会回答：最难的不是把 demo 跑起来，而是稳定地让“正确上下文进入 Prompt”。

具体难点：

- 文档质量差：PDF 解析乱、表格丢结构、重复内容多。
- 用户问题复杂：口语化、缩写、多轮指代、跨域问题。
- 检索不稳定：向量召回相似但不相关，关键词召回精确但不懂语义。
- 上下文取舍难：放少了漏信息，放多了污染 Prompt。
- 评测难：没有标注集就不知道优化有没有效果。
- 线上可观测难：回答错了要定位是重写错、意图错、召回错、rerank 错还是生成错。
- 权限和安全：企业知识库必须做用户级权限过滤。

结合项目可以收尾：

```text
所以我在项目里重点做了问题重写、意图识别、多路检索、rerank、会话记忆、限流熔断和链路追踪。它们不是堆功能，而是围绕 RAG 线上落地的几个核心风险：召回质量、成本、延迟、稳定性和可观测性。
```


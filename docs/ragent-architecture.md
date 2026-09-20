# Ragent RAG 系统架构文档

> 本文档描述 Ragent 项目的意图识别与 RAG 检索增强生成系统的完整架构，供接入 Agent 和 LangChain4j 时参考。

---

## 1. 项目概览

### 1.1 基本信息

| 项目 | 说明 |
|------|------|
| Group/Artifact | `com.nageoffer.ai:ragent` v0.0.1-SNAPSHOT |
| Java | 17 |
| 框架 | Spring Boot 3.5.7 |
| 向量数据库 | Milvus 2.6.6 / PostgreSQL pgvector |
| ORM | MyBatis-Plus 3.5.14 |
| 消息队列 | RocketMQ |
| 文档解析 | Apache Tika 3.2.3 |
| 权限 | Sa-Token |

### 1.2 模块结构

```
ragent/
├── framework/       # 共享基础设施（缓存、上下文、DTO、异常、MQ、分布式ID、Trace）
├── infra-ai/        # AI 基础设施层（LLM Chat/Embedding/Rerank 客户端抽象）
├── mcp-server/      # 独立的 MCP Server 应用（暴露 Tool 给 bootstrap 调用）
├── bootstrap/       # 主应用（363 个 Java 文件，含全部业务逻辑）
└── frontend/        # 前端（React + TypeScript）
```

### 1.3 bootstrap 模块核心包结构

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── core/            # 文档解析、分块
├── knowledge/       # 知识库管理（CRUD、调度、MQ 消费）
├── ingestion/       # 文档摄取管道（DAG 引擎、Fetcher/Parser/Chunker/Enhancer/Enricher/Indexer 6 节点）
├── rag/             # ★ RAG 核心逻辑（意图识别、检索、Prompt、MCP、记忆、Trace）
│   ├── config/      #   配置属性
│   ├── constant/    #   常量（阈值、模板路径）
│   ├── controller/  #   REST 接口
│   ├── core/
│   │   ├── intent/  #   意图识别 ★
│   │   ├── rewrite/ #   查询改写 ★
│   │   ├── retrieve/#   检索引擎 ★
│   │   ├── guidance/#   歧义引导 ★
│   │   ├── prompt/  #   Prompt 组装 ★
│   │   ├── mcp/     #   MCP 集成 ★
│   │   ├── memory/  #   对话记忆
│   │   └── vector/  #   向量存储
│   ├── dao/         #   数据访问层
│   ├── dto/         #   数据传输对象
│   ├── enums/       #   枚举（IntentKind、IntentLevel）
│   ├── eval/        #   评测框架
│   ├── service/     #   服务层（含 StreamChatPipeline ★）
│   └── trace/       #   可观测性
├── admin/           # 管理后台
└── user/            # 用户与认证
```

---

## 2. 完整 RAG 对话管道（StreamChatPipeline）

入口：`RAGChatServiceImpl.streamChat()` → `StreamChatPipeline.execute()`

管道共 **8 个阶段**，3 个短路分支：

```
用户问题
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Stage 1: loadMemory()                                        │
│   加载对话历史 + 摘要，追加当前用户消息到存储                   │
│   输入: conversationId, userId, question                      │
│   输出: List<ChatMessage> history                             │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Stage 2: rewriteQuery()                                      │
│   QueryRewriteService.rewriteWithSplit(question, history)     │
│   1) QueryTermMappingService.normalize() - 同义词/缩写标准化   │
│   2) LLM 改写 + 拆分子问题 (prompt/user-question-rewrite.st)  │
│   3) LLM 失败时 fallback: 标点符号规则拆分                     │
│   输出: RewriteResult(rewrittenQuestion, List<subQuestions>)  │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Stage 3: resolveIntents()                                    │
│   IntentResolver.resolve(rewriteResult)                       │
│   对每个子问题并行调用 LLM 意图分类                             │
│   过滤: score < 0.35 丢弃，每个子问题最多 3 个意图              │
│   输出: List<SubQuestionIntent>                               │
└──────────────────────────────────────────────────────────────┘
  │
  ├── [短路1] handleGuidance() ──→ 歧义引导提示返回给用户
  ├── [短路2] handleSystemOnly() ──→ 系统对话直接回复（无检索）
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Stage 6: retrieve()                                          │
│   RetrievalEngine.retrieve(subIntents, topK)                  │
│   1) 按子问题并行，每个子问题分离 KB / MCP 意图                  │
│   2) KB 检索: MultiChannelRetrievalEngine → PostProcessors     │
│   3) MCP 调用: 参数提取 → 工具执行 → 结果格式化                 │
│   输出: RetrievalContext(mcpContext, kbContext, intentChunks)  │
└──────────────────────────────────────────────────────────────┘
  │
  ├── [短路3] handleEmptyRetrieval() ──→ "未检索到相关文档"
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Stage 8: streamRagResponse()                                 │
│   1) RAGPromptService 选择场景 (KB_ONLY/MCP_ONLY/MIXED)       │
│   2) 选择 Prompt 模板（意图节点级自定义 > 场景默认）            │
│   3) 组装消息: [system] + [history] + [evidence + question]   │
│   4) LLMService.streamChat() 流式输出                          │
│   MCP 场景: temperature=0.3, topP=0.8                         │
│   KB 场景:  temperature=0,   topP=1.0                         │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 意图识别系统（Intent Recognition）

### 3.1 核心概念

意图识别使用 **树形结构 + LLM 分类** 的混合方案：

- **意图树**（Intent Tree）：3 级层次结构，存储在 MySQL `t_intent_node` 表 + Redis 缓存
- **意图类型**（IntentKind）：`KB`（知识库检索）、`MCP`（工具调用）、`SYSTEM`（系统对话）
- **意图层级**（IntentLevel）：`DOMAIN`（领域）→ `CATEGORY`（分类）→ `TOPIC`（主题）
- **叶子节点**：只有叶子节点参与分类，包含知识库 collection、MCP toolId、自定义 Prompt 等信息

### 3.2 意图树结构示例

```
集团信息化 (DOMAIN)
├── 人事 (CATEGORY)
│   ├── 招聘政策 (TOPIC, KB) ──→ collection: hr_recruitment
│   ├── 薪酬福利 (TOPIC, KB) ──→ collection: hr_compensation
│   └── 考勤制度 (TOPIC, KB) ──→ collection: hr_attendance
├── IT (CATEGORY)
│   ├── 系统介绍 (TOPIC, KB)   ──→ collection: it_systems
│   └── 技术支持 (TOPIC, KB)   ──→ collection: it_support
└── 财务 (CATEGORY)
    ├── 报销流程 (TOPIC, KB)   ──→ collection: finance_reimbursement
    └── 发票信息 (TOPIC, KB)   ──→ collection: finance_invoice
        └── 自定义 Prompt: "你是一个财务发票助手..."

业务系统 (DOMAIN)
├── OA 系统 (CATEGORY)
│   └── 数据安全 (TOPIC, KB)   ──→ collection: oa_security
└── 保险系统 (CATEGORY)
    └── 数据安全 (TOPIC, KB)   ──→ collection: insurance_security

MCP 工具 (DOMAIN)
└── 销售数据 (TOPIC, MCP)      ──→ toolId: sales_data_query

系统交互 (DOMAIN)
├── 打招呼 (TOPIC, SYSTEM)
└── 关于机器人 (TOPIC, SYSTEM)
```

> **注意**：「数据安全」同时出现在 OA 系统和保险系统下——这是歧义引导（Guidance）的典型触发场景。

### 3.3 意图分类流程

```
DefaultIntentClassifier.classifyTargets(question)
  │
  ├── 1. loadIntentTreeData()
  │      Redis "ragent:intent:tree" (7天 TTL)
  │      └── miss → IntentNodeMapper 查 MySQL → 构建树(fillFullPath) → 写 Redis
  │
  ├── 2. buildPrompt(leafNodes)
  │      加载 prompt/intent-classifier.st 模板
  │      填充 ${intent_list}$: id, path, name, description, examples, type
  │
  ├── 3. LLM 调用 → 返回 JSON:
  │      [{"id":"node_001", "score":0.92, "reason":"用户明确询问招聘政策"}]
  │
  └── 4. 解析并返回 List<NodeScore>
```

### 3.4 意图解析与限制（IntentResolver）

```java
// 核心常量
INTENT_MIN_SCORE = 0.35;   // 意图最低分数阈值
MAX_INTENT_COUNT = 3;      // 单次查询最多参与意图数

// 流程
IntentResolver.resolve(rewriteResult):
  1. 对每个子问题并行调用 classifyIntents()
  2. 每个子问题过滤 score >= 0.35，取 top-3
  3. capTotalIntents(): 全局限制最多 3 个意图
     - 每个子问题至少保留 1 个（保底）
     - 剩余配额按分数从高到低分配
  4. 输出 List<SubQuestionIntent>
```

### 3.5 意图分组（IntentGroup）

```java
IntentResolver.mergeIntentGroup(subIntents):
  // 将所有子问题的意图分为两组
  mcpIntents = filter(isMCP && hasMcpToolId)
  kbIntents  = filter(isKB)
  → IntentGroup(mcpIntents, kbIntents)
```

这个分组结果直接决定：
- **检索策略**：KB 意图走多通道向量检索，MCP 意图走工具调用
- **Prompt 场景**：全 KB → `KB_ONLY`，全 MCP → `MCP_ONLY`，混合 → `MIXED`

### 3.6 意图节点数据结构

```java
IntentNode {
    String id;                 // 唯一标识
    String kbId;               // 关联知识库 ID (KB 类型)
    String name;               // 节点名称
    String description;        // 描述
    IntentLevel level;         // DOMAIN / CATEGORY / TOPIC
    String parentId;           // 父节点 ID
    List<String> examples;     // 示例问法（用于 LLM 分类）
    List<IntentNode> children; // 子节点
    String fullPath;           // 完整路径 "集团信息化 > 人事 > 招聘政策"
    IntentKind kind;           // KB / MCP / SYSTEM
    String collectionName;     // Milvus Collection 名称 (KB 类型)
    String mcpToolId;          // MCP 工具 ID (MCP 类型)
    Integer topK;              // 该意图的检索数量（覆盖全局 topK）
    String promptSnippet;      // 意图专属 Prompt 片段（用于上下文格式化）
    String promptTemplate;     // 意图专属完整 Prompt 模板（覆盖默认模板）
    String paramPromptTemplate;// MCP 参数提取的自定义 Prompt
}
```

### 3.7 意图树批量导入脚本

意图树原本只能在管理后台逐个节点手工创建。`scripts/` 下提供了一组 PowerShell 脚本，用于把**本地语料目录**映射成「知识库 → 领域 → 分类 → 主题」的三级意图树，并通过 REST 接口批量创建节点。

#### 3.7.1 脚本清单

| 文件 | 作用 |
|------|------|
| `scripts/huda-intent-tree.psm1` | 核心模块：意图定义表、语料盘点、意图计划生成、知识库映射、节点导入 |
| `scripts/import-huda-intent-tree.ps1` | 命令行入口：解析参数、加载知识库映射、打印计划、执行导入 |
| `scripts/tests/huda-intent-tree.Tests.ps1` | 断言脚本：校验计划节点数、intentCode 唯一性、根节点数、TOPIC 必须绑定 kbId、语料盘点结果 |

模块导出 4 个函数：

| 函数 | 说明 |
|------|------|
| `Get-HudaCorpusInventory` | 盘点语料目录：总文件数、可用文件数、被忽略的元数据/媒体文件数、待复核文件数，以及每个知识库目录下的文件数 |
| `Get-HudaIntentPlan` | 按意图定义表生成节点列表（含层级、父节点、排序、sourceFolders 元数据） |
| `Get-HudaKnowledgeBaseMap` | 调用 `GET /knowledge-base` 拉取「知识库名称 → ID」映射 |
| `Invoke-HudaIntentTreeImport` | 拉取现有意图树，对计划节点执行 CREATE / UPDATE / SKIP |

#### 3.7.2 生成的树形结构

每个领域根节点固定生成三级结构，`level` / `kind` 取值与 `IntentLevel`（DOMAIN=0 / CATEGORY=1 / TOPIC=2）、`IntentKind`（KB=0）枚举一致：

```
<领域名> (DOMAIN, level=0, 无 kbId)
└── <领域名>主题 (CATEGORY, level=1, 无 kbId)
    └── <主题名> (TOPIC, level=2, kind=KB, kbId=<按名称匹配的知识库ID>, topK=指定值)
```

只有 level=2 的 TOPIC 节点会绑定 `kbId` 且携带 `examples` 示例问法——这与 §3.1 中「只有叶子节点参与分类」的设计一致：`examples` 直接进入 `prompt/intent-classifier.st` 的意图列表，用于提升 LLM 分类准确率。

当前内置 5 个领域，共 38 个节点（5 DOMAIN + 5 CATEGORY + 28 TOPIC）：

| 领域（intentCode 前缀） | 分类数 | 语料来源目录 |
|------------------------|--------|-------------|
| 湖大通用概况与校园生活（`hnu-general-life`） | 7 | `1.湖大概况介绍`、`2.湖大新生关注汇总-生活篇` |
| 湖大本科教学与学业制度（`hnu-undergraduate-rules`） | 8 | `3.湖大新生重点文件汇总-学业篇` |
| 湖大本科专业培养方案（`hnu-undergraduate-programs`） | 1 | `4.湖大所有专业培养方案汇总` |
| 湖大奖助学金资助（`hnu-undergraduate-funding`） | 5 | `5.湖大奖、助学金篇` |
| 湖大研究生新生与研究生管理（`hnu-graduate-management`） | 7 | `6.湖大研究生新生专属-核心篇` |

节点的 `intentCode` 命名规则为 `<领域Code>`、`<领域Code>-topics`、`<领域Code>-<主题Suffix>`；`sortOrder` 按定义表顺序全局自增，保证管理后台树形展示顺序稳定。

#### 3.7.3 语料盘点规则

`Get-HudaCorpusInventory` 会遍历语料根目录，并按扩展名分类：

- **忽略**（不计入可用节点）：`.ini`、`.mp3`、`.mp4`，以及任意层级的 `desktop.ini`
- **待复核**：`.zip`、`.doc`、`.xls`——这些格式无法被现有解析链路稳定处理，会单独列出路径供人工确认
- 若意图定义中的任一 `SourceDirs` 目录不存在，脚本直接报错，避免生成指向空目录的意图节点

#### 3.7.4 依赖的接口

脚本只使用既有的管理端 REST 接口，不引入新的服务端逻辑：

| 接口 | 用途 |
|------|------|
| `GET /knowledge-base?current=1&size=100` | 拉取知识库列表，构建「名称 → kbId」映射 |
| `GET /intent-tree/trees` | 拉取完整意图树，用于判断节点是否已存在（按 `intentCode` 匹配） |
| `POST /intent-tree` | 创建节点，请求体对应 `IntentNodeCreateRequest` |
| `PUT /intent-tree/{id}` | 更新已存在的节点，请求体对应 `IntentNodeUpdateRequest` |

注意：`intentCode` 与 `kbId` 只在创建时写入、更新接口不可变更，因此**领域/分类/主题必须按父节点在前的顺序创建**（子节点依赖父节点的 `parentCode` 已存在），计划列表已按此顺序生成。

#### 3.7.5 使用方式

```powershell
# 1) 预演：只拉取现有树并打印计划，不发送任何写请求
$env:RAGENT_AUTHORIZATION = '<前端 Authorization 头>'
pwsh -File scripts/import-huda-intent-tree.ps1 `
  -BaseUrl 'http://localhost:9090/api/ragent' `
  -SourceRoot 'D:\study\研一资料\湖大新生文件' `
  -DryRun

# 2) 确认节点列表无误后执行写入
pwsh -File scripts/import-huda-intent-tree.ps1 `
  -SourceRoot 'D:\study\研一资料\湖大新生文件' `
  -Apply

# 3) 需要覆盖已存在节点的名称/描述/示例时，追加 -UpdateExisting
```

主要参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-BaseUrl` | `http://localhost:9090/api/ragent` | 对应 `server.servlet.context-path` |
| `-SourceRoot` | 本地语料根目录 | 必须包含意图定义中声明的所有子目录 |
| `-Authorization` | `$env:RAGENT_AUTHORIZATION` | 必填，作为前端 Authorization 头透传，脚本不会打印其值 |
| `-KnowledgeBaseMapPath` | 空 | 传入 JSON 文件可跳过 `GET /knowledge-base`，用于离线演练 |
| `-TopK` | `8` | 写入 TOPIC 节点的 `topK`，覆盖全局 `defaultTopK` |
| `-DryRun` / `-Apply` | 都未指定时等同 DryRun | 两者互斥；未加 `-Apply` 时不会发送任何写请求 |
| `-UpdateExisting` | 关闭 | 默认对已存在节点输出 SKIP，保证脚本可重复执行 |

#### 3.7.6 注意事项

- 脚本按**知识库名称精确匹配** kbId。若管理后台里知识库名称与意图定义表不一致，`Get-HudaIntentPlan` 会直接抛出「缺少知识库 ID」错误，不会静默创建无绑定节点。
- 导入完成后意图树缓存在 Redis（`ragent:intent:tree`，7 天 TTL）。若发现新节点未生效，需先清理该缓存再重试，参见 §3.3 的 `loadIntentTreeData()` 流程。
- TOPIC 节点绑定的是 `kbId`，因此对应的知识库必须先建好并完成文档灌库，否则节点参与分类后仍检索不到内容。
- `scripts/hnu-kb-intent-tree.test.mjs` 是同一批工作的 Node 版本测试，但其依赖的 `scripts/hnu-kb-intent-tree.mjs` 未纳入仓库，当前无法执行；可执行版本是 `scripts/tests/huda-intent-tree.Tests.ps1`。

---

## 4. 检索引擎（Retrieval Engine）

### 4.1 总体架构

```
RetrievalEngine.retrieve(subIntents, topK)
  │
  ├── 对每个 SubQuestionIntent 并行执行 buildSubQuestionContext():
  │   │
  │   ├── KB 检索
  │   │   MultiChannelRetrievalEngine.retrieveKnowledgeChannels()
  │   │   │
  │   │   ├── Stage 1: 并行执行所有启用的 SearchChannel
  │   │   │   ├── IntentDirectedSearchChannel (priority=1)
  │   │   │   │   条件: KB 意图存在 && score >= minIntentScore(0.4)
  │   │   │   │   策略: IntentParallelRetriever → 意图对应的 Collection 向量检索
  │   │   │   │
  │   │   │   └── VectorGlobalSearchChannel (priority=10)
  │   │   │       条件: 意图置信度低 (maxScore < 0.6)
  │   │   │       或 单意图 score < 0.8 时作为补充
  │   │   │       或 IntentDirected 被禁用时作为兜底
  │   │   │       策略: CollectionParallelRetriever → 所有 KB Collection 全量检索
  │   │   │
  │   │   └── Stage 2: 后处理链
  │   │       ├── DeduplicationPostProcessor (order=1)  按 ID 去重，保留最高分
  │   │       └── RerankPostProcessor (order=10)        Rerank 模型重排序
  │   │
  │   ├── ContextFormatter.formatKbContext()
  │   │   单意图: node.promptSnippet + chunk 文本
  │   │   多意图: 合并 snippet + 游标去重 chunk
  │   │   无意图: 平铺 chunk 文本
  │   │
  │   └── MCP 调用（如果有 MCP 意图）
  │       │
  │       ├── LLMMcpParameterExtractor.extractParameters(question, toolDef, customPrompt?)
  │       │   基于工具 JSON Schema + 用户问题，LLM 提取参数
  │       │   填充 schema 中的默认值
  │       │
  │       ├── McpClientToolExecutor.execute(params)
  │       │   通过 MCP Java SDK (McpSyncClient) 调用远程 MCP Server
  │       │
  │       └── ContextFormatter.formatMcpContext()
  │           成功: 用 mcp-section 模板渲染结构化数据
  │           失败: 用 mcp-error 模板渲染错误信息
  │
  └── 合并所有子问题上下文:
      单子问题: 直接使用
      多子问题: 用 sub-question-kb-wrapper / sub-question-mcp-wrapper 包裹编号
      → RetrievalContext(mcpContext, kbContext, intentChunks)
```

### 4.2 检索通道配置

```yaml
rag:
  search:
    defaultTopK: 10
    vectorGlobal:
      enabled: true
      confidenceThreshold: 0.6       # 低于此值触发全局检索
      singleIntentSupplementThreshold: 0.8  # 低于此值全局补充
      topKMultiplier: 3
    intentDirected:
      enabled: true
      minIntentScore: 0.4            # 意图分数低于此值不触发定向检索
      topKMultiplier: 2
  rerankEnabled: true                 # Rerank 功能开关
  queryRewriteEnabled: true           # 查询改写开关
```

### 4.3 检索引擎的并行策略

有两个独立的线程池：

| 线程池 | 用途 | 并发粒度 |
|--------|------|---------|
| `ragContextExecutor` | 子问题上下文构建 | 每个 `SubQuestionIntent` 一个任务 |
| `mcpBatchExecutor` | MCP 工具调用 | 每个 MCP 工具一个任务 |
| `intentClassifyExecutor` | 意图分类 | 每个子问题一个任务 |
| `ragRetrievalExecutor` | 多通道检索 | 每个 SearchChannel 一个任务 |

---

## 5. MCP 集成（Model Context Protocol）

### 5.1 架构

```
bootstrap (MCP Client)              mcp-server (独立应用)
┌─────────────────────────┐         ┌──────────────────────────┐
│ McpClientAutoConfiguration│         │ McpServerConfig           │
│   @PostConstruct          │         │   ↓                       │
│   连接每个 MCP Server     │◄────────│ 暴 露 Tool API            │
│   listTools()             │  MCP    │                           │
│   注册 McpClientToolExecutor│ Protocol│ SalesMcpExecutor         │
│       ↓                   │         │ TicketMcpExecutor         │
│ DefaultMcpToolRegistry   │         │ WeatherMcpExecutor        │
│   Map<toolId, Executor>  │         └──────────────────────────┘
└─────────────────────────┘
```

### 5.2 MCP 配置

```yaml
rag:
  mcp:
    servers:
      - name: sales-server
        url: http://localhost:8081/mcp
      - name: ticket-server
        url: http://localhost:8082/mcp
```

### 5.3 MCP 调用流程

```
检索时发现 MCP 意图
  │
  ├── 从 McpToolRegistry 获取 McpToolExecutor
  │
  ├── LLM 参数提取:
  │   prompt/mcp-parameter-extract.st (system)
  │   + prompt/mcp-parameter-extract-user.st (user, 含 {tool_definition} + {user_question})
  │   → LLM 返回 JSON 参数 → 填充 schema 默认值 → Map<String, Object>
  │
  ├── executor.execute(params)
  │   → McpSyncClient.callTool(CallToolRequest)
  │   → CallToolResult
  │
  └── DefaultContextFormatter.formatMcpContext()
      → 用 context-format.st 的 mcp-section 模板渲染
```

---

## 6. 歧义引导（Guidance）

### 6.1 触发条件

当单个子问题有 2+ KB 意图且它们的分数接近时：

```
IntentGuidanceService.detectAmbiguity(question, subIntents):
  
  取 top-2 KB 意图分数 ratio = secondScore / firstScore
  
  三级判定:
    ratio < threshold - margin  (ratio < 0.65)  → 明确不歧义：不引导
    ratio >= threshold          (ratio >= 0.8)  → 明确歧义：生成引导
    0.65 <= ratio < 0.8                         → 灰色地带：调 LLM 二次确认
```

### 6.2 配置

```yaml
rag:
  guidance:
    enabled: true
    ambiguityScoreRatio: 0.8     # 歧义判断阈值
    ambiguityMargin: 0.15        # 灰色地带宽度
    maxOptions: 6                # 引导选项最多数量
```

---

## 7. Prompt 模板系统

### 7.1 模板文件

所有模板位于 `bootstrap/src/main/resources/prompt/*.st`，使用 Mustache 风格 `{key}` 占位符。

| 模板文件 | 用途 | 场景 |
|---------|------|------|
| `intent-classifier.st` | 意图分类 | 发送所有意图叶子节点给 LLM 评分 |
| `user-question-rewrite.st` | 查询改写+拆分 | LLM 改写问题并拆分子问题 |
| `answer-chat-kb.st` | KB 回答 | KB_ONLY 场景默认系统 Prompt |
| `answer-chat-mcp.st` | MCP 回答 | MCP_ONLY 场景默认系统 Prompt |
| `answer-chat-mcp-kb-mixed.st` | 混合回答 | MIXED 场景默认系统 Prompt |
| `answer-chat-system.st` | 系统对话 | 打招呼、自我介绍等无需检索场景 |
| `context-format.st` | 上下文格式化 | 多 section 模板（见下方） |
| `guidance-prompt.st` | 歧义引导 | 生成让用户选择的引导语 |
| `guidance-ambiguity-check.st` | 歧义确认 | 灰色地带调 LLM 确认 |
| `mcp-parameter-extract.st` | 参数提取 System | MCP 工具参数提取 |
| `mcp-parameter-extract-user.st` | 参数提取 User | 含工具定义和用户问题 |
| `conversation-summary.st` | 对话摘要 | 长对话压缩 |
| `conversation-title.st` | 标题生成 | 会话自动命名 |

### 7.2 context-format.st 的多 Section 结构

```markdown
--- section: kb-section ---
### {title}
{snippets}
{chunks}

--- section: snippet-rules ---
> 在回答关于 {intent_name} 的问题时，请参考以下规则：{snippet}

--- section: mcp-section ---
## 来自 MCP 查询 "{tool_name}" 的数据：
{body}

--- section: mcp-intent-rules ---
> 在解读 {tool_name} 数据时请遵循以下规则：{snippet}

--- section: mcp-error ---
## MCP 查询 "{tool_name}" 执行异常：
{error_message}

--- section: sub-question-kb-wrapper ---
### 子问题 {index}：{question}
{context}

--- section: sub-question-mcp-wrapper ---
### 子问题 {index}：{question}（相关数据）
{context}

--- section: mcp-evidence ---
## 相关数据
{body}

--- section: kb-evidence ---
## 相关文档
{body}

--- section: multi-questions ---
{questions}

--- section: single-question ---
{question}
```

模板加载：`PromptTemplateLoader` 缓存加载，`renderSection(path, section, slots)` 按 section 渲染。

### 7.3 Prompt 场景选择

```
RAGPromptService.plan(context)
  │
  ├── hasMcp && !hasKb  → MCP_ONLY
  │   ├── 单意图 + 有 promptTemplate → 使用节点自定义模板
  │   └── 否则 → answer-chat-mcp.st
  │
  ├── !hasMcp && hasKb  → KB_ONLY
  │   ├── 单意图 + 有 promptTemplate → 使用节点自定义模板
  │   └── 否则 → answer-chat-kb.st
  │
  └── hasMcp && hasKb   → MIXED → answer-chat-mcp-kb-mixed.st
```

### 7.4 消息组装顺序

```
RAGPromptService.buildStructuredMessages():
  [1] System Message  ← buildSystemPrompt() 选出的系统 Prompt
  [2] History Messages ← 对话历史（第一条是摘要 system message）
  [3] User Message    ← mergeEvidenceAndQuestion():
       ┌─────────────────────────────┐
       │ ## 相关数据                  │  ← mcpContext（如果有）
       │ ...                          │
       │                              │
       │ ## 相关文档                  │  ← kbContext（如果有）
       │ ...                          │
       │                              │
       │ 用户问题 / 编号子问题列表     │  ← question
       └─────────────────────────────┘
```

---

## 8. 对话记忆（Conversation Memory）

```
ConversationMemoryService.loadAndAppend(conversationId, userId, message)
  │
  ├── 并行加载:
  │   ├── JdbcConversationMemoryStore.load()  → 最近 N 条消息
  │   └── ConversationMemorySummaryService    → 历史摘要（system message）
  │
  ├── 摘要生成触发条件: 消息数 > memorySummaryThreshold (默认 8)
  │   └── LLM 用 conversation-summary.st 生成摘要
  │
  └── 追加当前消息到存储（异步）
```

历史消息在最终 Prompt 中的位置：紧跟 system prompt 之后，evidence 之前。

---

## 9. 数据流总览

```
RAGChatController (SSE Endpoint)
  │
  ▼
RAGChatServiceImpl.streamChat()
  │ ChatQueueLimiter (公平分布式限流)
  ▼
StreamChatPipeline.execute(ctx)
  │
  ├─(1)─ memoryService.loadAndAppend()          → history
  ├─(2)─ queryRewriteService.rewriteWithSplit() → RewriteResult
  ├─(3)─ intentResolver.resolve()               → List<SubQuestionIntent>
  │       └─ DefaultIntentClassifier.classifyTargets()
  │           └─ LLM: prompt/intent-classifier.st
  ├─(4)─ guidanceService.detectAmbiguity()      → GuidanceDecision [短路]
  ├─(5)─ systemOnly check                       → direct system response [短路]
  ├─(6)─ retrievalEngine.retrieve()             → RetrievalContext
  │       ├─ KB: MultiChannelRetrievalEngine
  │       │    ├─ IntentDirectedSearchChannel → IntentParallelRetriever
  │       │    ├─ VectorGlobalSearchChannel → CollectionParallelRetriever
  │       │    └─ PostProcessors: Dedup → Rerank
  │       └─ MCP: LLMMcpParameterExtractor → McpClientToolExecutor
  ├─(7)─ empty check                            → "未检索到文档" [短路]
  └─(8)─ RAGPromptService.buildStructuredMessages()
          │ plan() → scene (KB_ONLY/MCP_ONLY/MIXED) → template
          │ buildEvidenceBody() → mcp + kb context
          └─→ LLMService.streamChat() → SSE Stream
```

---

## 10. 配置要点总览

```yaml
rag:
  # 查询改写
  queryRewriteEnabled: true
  
  # Rerank
  rerankEnabled: true
  
  # 意图
  intent:
    minScore: 0.35        # RAGConstant.INTENT_MIN_SCORE
    maxCount: 3            # RAGConstant.MAX_INTENT_COUNT
  
  # 检索通道
  search:
    defaultTopK: 10
    vectorGlobal:
      enabled: true
      confidenceThreshold: 0.6
      singleIntentSupplementThreshold: 0.8
      topKMultiplier: 3
    intentDirected:
      enabled: true
      minIntentScore: 0.4
      topKMultiplier: 2
  
  # 歧义引导
  guidance:
    enabled: true
    ambiguityScoreRatio: 0.8
    ambiguityMargin: 0.15
    maxOptions: 6
  
  # MCP 服务器
  mcp:
    servers:
      - name: xxx
        url: http://...
  
  # 记忆
  memory:
    summaryThreshold: 8    # 超过此数量触发摘要
```

---

## 11. 关键接口与扩展点

### 11.1 当前架构中的扩展点

| 扩展点 | 接口/抽象类 | 说明 |
|--------|------------|------|
| 意图分类器 | `IntentClassifier` | 当前仅 `DefaultIntentClassifier`（LLM 串行），可替换为向量/规则分类器 |
| 查询改写 | `QueryRewriteService` | 当前仅 `MultiQuestionRewriteService` |
| 检索通道 | `SearchChannel` | 当前有 `IntentDirectedSearchChannel` + `VectorGlobalSearchChannel`，可添加 ES 关键词、Hybrid 等 |
| 检索后处理 | `SearchResultPostProcessor` | 当前有 Dedup + Rerank，可添加多样性、新鲜度等 |
| 上下文格式化 | `ContextFormatter` | 当前仅 `DefaultContextFormatter` |
| MCP 工具注册 | `McpToolRegistry` | 当前仅 `DefaultMcpToolRegistry`（内存 Map） |
| MCP 参数提取 | `McpParameterExtractor` | 当前仅 `LLMMcpParameterExtractor` |
| 对话记忆存储 | `ConversationMemoryStore` | 当前仅 `JdbcConversationMemoryStore` |
| 向量存储 | `VectorStoreService` | 当前有 Milvus + PgVector 两种实现 |
| Prompt 模板加载 | `PromptTemplateLoader` | 基于类路径 .st 文件 + 内存缓存 |

### 11.2 与 Agent / LangChain4j 集成的关键接触点

当前系统是一个 **固定的线性管道**，以下是接入 Agent 框架需要关注的点：

1. **管道编排**：`StreamChatPipeline` 是硬编码的 8 阶段顺序执行，Agent 框架通常需要动态决策路径
2. **意图识别**：当前的意图树 + LLM 分类相当于 Agent 的 "Router"，但分类粒度是静态树而非动态工具选择
3. **MCP 工具调用**：当前只有在检索阶段触发，且参数提取和执行是分离的（LLM 提取参数 → 执行 → 格式化）。Agent 框架通常使用 `tool_use` 原生能力
4. **多步推理**：当前不支持 Agent 的多轮工具调用循环（think → act → observe → think）
5. **Prompt 模板**：当前为场景化固定模板，Agent 框架通常使用更动态的 Prompt 构建
6. **上下文窗口**：当前在检索阶段截断上下文，Agent 框架可能需要更智能的上下文管理

---

## 12. infra-ai 层说明

`infra-ai` 模块提供了统一的 LLM 客户端抽象，支持多 Provider 路由：

```
LLMService (Facade)
  └── RoutingLLMService (路由)
       ├── SiliconFlowChatClient    (OpenAI 兼容 API)
       ├── OllamaChatClient          (本地 Ollama)
       ├── BaiLianChatClient         (阿里百炼)
       └── AIHubMixChatClient        (AIHubMix)

EmbeddingService (Facade)
  └── RoutingEmbeddingService (路由)
       ├── SiliconFlowEmbeddingClient
       ├── OllamaEmbeddingClient
       └── AIHubMixEmbeddingClient

RerankService (Facade)
  └── RoutingRerankService (路由)
       ├── BaiLianRerankClient
       └── NoopRerankClient
```

所有 Chat/Embedding/Rerank 客户端都通过 `ModelRoutingExecutor` + `ModelSelector` + `ModelHealthStore` 进行健康感知的路由选择。

当前的流式调用签名：
```java
StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback);

// ChatRequest 结构
ChatRequest {
    List<ChatMessage> messages;   // system + user + assistant 消息列表
    Double temperature;
    Double topP;
    Boolean thinking;             // 深度思考模式
}
```

---

## 13. 接入 Agent 的建议切入点

基于以上架构分析，接入 Agent + LangChain4j 可以从以下几个层面考虑：

### 层面 1：替换管道编排
- 用 LangChain4j 的 `AiServices` / `Agent` 替换 `StreamChatPipeline` 的硬编码管道
- Agent 的 `Tool` 机制替代当前的 MCP 工具调用流程

### 层面 2：统一 Tool 层
- 将 KB 检索也包装为 Tool（`searchKnowledgeBase` tool）
- 将当前的 `SearchChannel` 体系映射为 LangChain4j 的 `Tool` 或 `Retriever`
- 利用 Agent 的原生 `tool_use` 替代当前的 LLM 参数提取 + 手动执行

### 层面 3：动态路由
- 用 Agent 的动态决策替代当前的静态意图树 + Prompt 场景选择
- Agent 自行决定：是否需要检索、检索哪个知识库、是否调用工具

### 层面 4：保留的组件
- `infra-ai` 层（LLM/Embedding/Rerank 客户端）可作为 LangChain4j 的 `ChatModel` / `EmbeddingModel` 适配
- 向量存储（Milvus/PgVector）可作为 LangChain4j 的 `EmbeddingStore`
- Prompt 模板系统可迁移到 LangChain4j 的 `PromptTemplate`
- 对话记忆可适配 LangChain4j 的 `ChatMemory`

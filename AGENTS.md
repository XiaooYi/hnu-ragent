# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build all modules (skipping tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean package

# Run a single test class
./mvnw test -pl bootstrap -Dtest=com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreServiceTest

# Run a single test method
./mvnw test -pl bootstrap -Dtest=YourTestClass#testMethodName

# Start the app (bootstrap module, port 9090)
./mvnw spring-boot:run -pl bootstrap

# Frontend dev server
cd frontend && npm run dev

# Frontend build
cd frontend && npm run build

# Code formatting (spotless-maven-plugin, bound to compile phase)
./mvnw spotless:apply
```

## Maven Multi-Module Structure

```
ragent (pom, parent)
├── framework    — shared infrastructure (cache, idempotent, trace, convention, exception, context)
├── infra-ai     — AI model abstraction (chat, embedding, rerank, model routing, circuit-breaker)
├── bootstrap    — main Spring Boot app, all business logic, entry point
└── mcp-server   — standalone MCP server process
```

Dependency direction: `bootstrap` → `framework` + `infra-ai`. `framework` and `infra-ai` have no inter-dependency.

## Architecture: Request Flow Through the RAG Chain

For the main chat endpoint (`GET /rag/v3/chat`):
```
RAGChatController → RAGChatServiceImpl → StreamChatPipeline.execute():
  1. loadMemory()         — ConversationMemoryService (sliding window + auto-summary)
  2. rewriteQuery()       — QueryRewriteService (rewrite + split into sub-questions)
  3. resolveIntents()     — IntentResolver (tree-based intent classification)
  4. handleGuidance()     — IntentGuidanceService (ambiguity detection, short-circuit)
  5. handleSystemOnly()   — Pure system response (no retrieval, short-circuit)
  6. retrieve()           — RetrievalEngine → MultiChannelRetrievalEngine (★ core retrieval)
  7. streamRagResponse()  — Prompt assembly + LLM streaming
```

## Retrieval Component Hierarchy (★ key for debug work)

```
RetrievalEngine              ← Orchestrates KB + MCP retrieval per sub-question
  └─ MultiChannelRetrievalEngine  ← coordinates channels + post-processors
       ├─ [Stage A] executeSearchChannels() — parallel channel fan-out
       │    ├─ IntentDirectedSearchChannel (priority=1, highest)
       │    │    └─ IntentParallelRetriever → RetrieverService.retrieve()
       │    └─ VectorGlobalSearchChannel   (priority=10, lower)
       │         └─ CollectionParallelRetriever → RetrieverService.retrieve()
       │
       └─ [Stage B] executePostProcessors() — sequential chain
            ├─ DeduplicationPostProcessor (order=1, always on)
            └─ RerankPostProcessor        (order=10, gated by `rag.rerank.enabled`)
                 └─ RerankService.rerank(query, candidates, topN) — also does TopK cutoff
```

Channel enabling logic:
- `IntentDirectedSearchChannel`: enabled in config (`rag.search.channels.intent-directed.enabled`) AND KB intents with score ≥ `minIntentScore` (0.4)
- `VectorGlobalSearchChannel`: enabled in config AND (intent-directed off OR max intent score < `confidenceThreshold`(0.6) OR single medium-confidence intent)

Important: `RerankPostProcessor` is the **only** place that performs TopK truncation. If `rag.rerank.enabled=false`, all merged+dudup'd chunks flow through — no limit.

## Extension Points (Spring Bean auto-discovery)

| Add a... | Implement | Registered |
|----------|-----------|------------|
| Search channel | `SearchChannel` interface | Auto-injected into `MultiChannelRetrievalEngine` |
| Post-processor | `SearchResultPostProcessor` interface | Auto-injected, sorted by `getOrder()` |
| MCP tool | `MCPToolExecutor` interface | Auto-discovered by `DefaultMCPToolRegistry` |
| Ingestion node | `IngestionNode` interface | Inserted into document processing pipeline |
| Model provider | `ChatClient` interface in infra-ai | Added to model routing candidate pool |

## Key Configuration

All config lives in `bootstrap/src/main/resources/application.yaml`, root prefix:
- `rag.search.*` — `SearchChannelProperties` (channel switches, thresholds, topK multipliers)
- `rag.rerank.enabled` — global Rerank flag (`RAGConfigProperties`)
- `rag.query-rewrite.enabled` — query rewrite toggle
- `rag.vector.type` — `pg` or `milvus`
- `rag.trace.enabled` — AOP-based full-chain tracing
- `ai.providers.*` + `ai.chat.candidates` — multi-model routing with priority
- `rag.rate-limit.global.*` — distributed queue-based rate limiting (Redis ZSET)

## Trace/Debug Infrastructure

- `@RagTraceNode` annotation on methods → `RagTraceAspect` AOP records timing/status to `t_rag_trace_node`
- Trace nodes have an `extraData` JSON string field (currently unused, available for debug metadata)
- `RagTraceController` exposes `GET /rag/traces/runs/{traceId}` for querying past runs
- Stream scenarios use `RagStreamTraceSupportImpl` for cross-thread span tracking
- Trace nodes only capture metadata (timing, status), **not** intermediate chunk results

## Package Conventions

- `controller/request/` — HTTP request DTOs; `controller/vo/` — HTTP response VOs
- `service/bo/` — service-layer business objects; `dto/` — cross-layer internal DTOs
- `dao/entity/` + `dao/mapper/` — MyBatis-Plus entities and mappers (no XML mappers)
- `core/` — domain logic independent of HTTP/web layer
- `config/` — `@Configuration` + `@ConfigurationProperties` classes
- Prompt templates as `.st` (StringTemplate4) files in `resources/prompt/`

# 知识图谱后端（LightRAG + Neo4j）本地编排

本目录存放**可选**的图谱检索后端编排材料。知识图谱默认关闭（`rag.graph.type=none`），
不部署本目录的服务时，整个项目的行为与「从未引入图谱」完全一致。

## 何时需要它

需要「多跳关系」类问答（实体关联、课程组归属、部门职责串联）时启用：

1. 起 LightRAG（含 Neo4j）：

   ```bash
   docker compose -f resources/docker/graphrag/lightrag-neo4j-stack.compose.yaml up -d
   ```

2. 在 `bootstrap/src/main/resources/application.yaml` 打开图谱后端与通道：

   ```yaml
   rag:
     graph:
       type: lightrag
       lightrag:
         base-url: http://127.0.0.1:9621   # 与上面 compose 暴露的端口一致
         query-mode: hybrid
     search:
       channels:
         graph:
           enabled: true
           mode: both
   ```

3. 重新入库（或对已有文档重跑分块）触发图谱写入：向量写入成功后由
   `GraphSyncingVectorStoreService` 装饰器同步投喂 LightRAG。

## 说明

- **本仓库不直连 Neo4j**：实体与关系抽取、图存储都在 LightRAG 侧完成，后端只调 HTTP 接口
  （`/query`、`/documents/*`、`/graphs`、`/graph/label/*`）。因此不需要在 Java 侧引入图数据库驱动。
- **workspace 是实例级的**：LightRAG 的 `/query` 没有 per-request workspace 参数，一次查询看的就是全图；
  知识库归属只能按 `file_path`（写入时编码为 `{collectionName}_{docId}`）在结果侧判定。
  若需要真正的子图隔离，需要按知识库部署多个 LightRAG 实例（属后续阶段）。
- **删除是 best-effort**：知识库 / 文档删除时会尽力清理对应图谱数据（按 `file_path` 反查内部 doc_id 后删除），
  失败只记 WARN；残留证据在检索侧会被归属判定拦掉，日志中体现为「过滤掉 N 条无主证据」。
- **密钥**：`LIGHTRAG_API_KEY` 走环境变量，不要写进配置文件。

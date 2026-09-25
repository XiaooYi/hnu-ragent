import assert from "node:assert/strict";
import { test } from "node:test";
import { build } from "esbuild";

const loadModule = async (entry) => {
  const result = await build({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: "esm",
    platform: "node",
    tsconfig: "tsconfig.app.json"
  });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
};

const { featureFlags, channelRows, pipelineRows, resolveRecallBudget, backendRows } = await loadModule(
  "src/lib/settingsRetrieval.ts"
);

test("能力开关按固定顺序展示，取值严格按布尔判定", () => {
  const rows = featureFlags({
    queryRewrite: true,
    rerank: false,
    citation: true,
    contextEnrich: false,
    trace: true
  });
  assert.deepEqual(
    rows.map((row) => [row.key, row.enabled]),
    [
      ["queryRewrite", true],
      ["rerank", false],
      ["citation", true],
      ["contextEnrich", false],
      ["trace", true]
    ]
  );
  assert.equal(featureFlags(null).length, 0);
  assert.equal(featureFlags(undefined).length, 0);
});

test("通道表：关键词通道默认关闭，阈值缺失显示 -", () => {
  const rows = channelRows({
    channels: {
      vectorGlobal: { enabled: true, confidenceThreshold: 0.6, singleIntentSupplementThreshold: 0.8 },
      intentDirected: { enabled: true, minIntentScore: 0.4 },
      keyword: { enabled: false, mode: "both" }
    }
  });
  assert.deepEqual(
    rows.map((row) => row.name),
    ["向量全局检索", "意图定向检索", "关键词检索"]
  );
  assert.equal(rows[2].enabled, "关闭");
  assert.equal(rows[1].detail, "只检索意图分 ≥ 0.4 的知识库");
  assert.equal(channelRows(null).length, 0);
  assert.equal(channelRows({}).length, 0);
});

test("检索管线参数展示与召回预算回退口径", () => {
  const search = {
    defaultTopK: 10,
    recallBudget: 0,
    channels: { timeoutMs: 15000 },
    fusion: { strategy: "rrf", rrfK: 60, rerankCandidateLimit: 50 },
    evidence: { minRerankScore: 0.2 }
  };
  assert.equal(resolveRecallBudget(search), 50, "recall-budget<=0 时跟随精排候选池上限");
  assert.equal(resolveRecallBudget({ ...search, recallBudget: 80 }), 80);
  assert.equal(resolveRecallBudget(null), null);

  const rows = pipelineRows(search);
  const byLabel = Object.fromEntries(rows.map((row) => [row.label, row.value]));
  assert.equal(byLabel["进入上下文条数（TopK）"], "10");
  assert.equal(byLabel["召回预算（通道取数深度）"], "50");
  assert.equal(byLabel["融合策略"], "rrf");
  assert.equal(byLabel["证据闸门最低精排分"], "0.2");
  assert.equal(byLabel["单通道超时"], "15000 ms");
  assert.equal(pipelineRows(null).length, 0);
});

test("后端选型行：缺失字段显示 -，不展示任何凭据", () => {
  const rows = backendRows({
    vector: { type: "pg" },
    keyword: { type: "es", index: "rag_keyword_store", uris: "http://127.0.0.1:9200", analyzer: "ik_max_word", searchAnalyzer: "ik_smart" },
    storage: { platform: "s3-compatible", endpoint: "http://127.0.0.1:9000" }
  });
  const byLabel = Object.fromEntries(rows.map((row) => [row.label, row.value]));
  assert.equal(byLabel["向量后端"], "pg");
  assert.equal(byLabel["关键词分词器"], "ik_max_word / ik_smart");
  assert.equal(byLabel["文件存储地址"], "http://127.0.0.1:9000");

  // 白名单式取值：即便接口多带了凭据字段，页面也不会渲染出来
  const withCredentials = backendRows({
    vector: { type: "pg" },
    keyword: { type: "es", accessKeyId: "AKIA-SECRET", password: "p@ss" },
    storage: { platform: "s3-compatible", endpoint: "http://127.0.0.1:9000", secretAccessKey: "AKIA-SECRET" }
  });
  assert.equal(
    withCredentials.some((row) => /AKIA-SECRET|p@ss/.test(row.value)),
    false,
    "页面不得渲染任何凭据"
  );

  const empty = Object.fromEntries(backendRows({ vector: {} }).map((row) => [row.label, row.value]));
  assert.equal(empty["向量后端"], "-");
  assert.equal(empty["关键词后端"], "-");
});

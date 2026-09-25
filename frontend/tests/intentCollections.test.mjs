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

const {
  normalizeCollectionNames,
  resolveIntentCollectionNames,
  addCollectionName,
  removeCollectionName,
  toggleCollectionName,
  buildIntentCollectionPayload
} = await loadModule("src/lib/intentCollections.ts");

test("新字段优先，旧单值字段兜底", () => {
  assert.deepEqual(
    resolveIntentCollectionNames({ collectionNames: ["kb_a", "kb_b"], collectionName: "kb_legacy" }),
    ["kb_a", "kb_b"]
  );
  assert.deepEqual(resolveIntentCollectionNames({ collectionName: "kb_legacy" }), ["kb_legacy"]);
  assert.deepEqual(resolveIntentCollectionNames({ collectionNames: [], collectionName: "  " }), []);
  assert.deepEqual(resolveIntentCollectionNames(null), []);
});

test("归一化会 trim、去空、去重且保序", () => {
  assert.deepEqual(normalizeCollectionNames([" kb_a ", "kb_b", "kb_a", "  ", null, undefined]), [
    "kb_a",
    "kb_b"
  ]);
});

test("添加、删除与切换 Collection", () => {
  assert.deepEqual(addCollectionName(["kb_a"], " kb_b "), ["kb_a", "kb_b"]);
  assert.deepEqual(addCollectionName(["kb_a"], "kb_a"), ["kb_a"]);
  assert.deepEqual(removeCollectionName(["kb_a", "kb_b"], "kb_a"), ["kb_b"]);
  assert.deepEqual(toggleCollectionName(["kb_a"], "kb_a"), []);
  assert.deepEqual(toggleCollectionName(["kb_a"], "kb_b"), ["kb_a", "kb_b"]);
});

test("提交负载同时写入新字段与旧单值字段", () => {
  assert.deepEqual(buildIntentCollectionPayload([" kb_a ", "kb_b"], 0), {
    collectionName: "kb_a",
    collectionNames: ["kb_a", "kb_b"]
  });
  assert.deepEqual(buildIntentCollectionPayload(["kb_a"], 0), {
    collectionName: "kb_a",
    collectionNames: ["kb_a"]
  });
  assert.deepEqual(buildIntentCollectionPayload([], 0), { collectionName: "", collectionNames: [] });
});

test("非知识库意图不携带 Collection", () => {
  assert.deepEqual(buildIntentCollectionPayload(["kb_a"], 1), { collectionName: "", collectionNames: [] });
  assert.deepEqual(buildIntentCollectionPayload(["kb_a"], 2), { collectionName: "", collectionNames: [] });
});

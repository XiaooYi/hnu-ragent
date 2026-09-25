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
  parseChangeDiff,
  formatDiffValue,
  summarizeChangeDiff,
  bizTypeLabel,
  operationTypeLabel,
  toQueryDateTime
} = await loadModule("src/lib/changeLogDiff.ts");

test("change_diff 解析：非法输入与空值都返回空数组", () => {
  assert.deepEqual(parseChangeDiff(null), []);
  assert.deepEqual(parseChangeDiff(undefined), []);
  assert.deepEqual(parseChangeDiff(""), []);
  assert.deepEqual(parseChangeDiff("not-json"), []);
  assert.deepEqual(parseChangeDiff("{}"), []);
  assert.deepEqual(parseChangeDiff("[]"), []);
});

test("change_diff 解析：保留字段路径与前后值，缺失字段回退为 /", () => {
  const rows = parseChangeDiff(
    JSON.stringify([
      { field: "/name", before: "旧名称", after: "新名称" },
      { field: "", before: null, after: 1 },
      { before: true, after: false }
    ])
  );
  assert.deepEqual(rows, [
    { field: "/name", before: "旧名称", after: "新名称" },
    { field: "/", before: null, after: 1 },
    { field: "/", before: true, after: false }
  ]);
});

test("差异值展示：空值显示 —，对象序列化为 JSON", () => {
  assert.equal(formatDiffValue(null), "—");
  assert.equal(formatDiffValue(undefined), "—");
  assert.equal(formatDiffValue(""), "「空」");
  assert.equal(formatDiffValue(0), "0");
  assert.equal(formatDiffValue(false), "false");
  assert.equal(formatDiffValue("湖大制度库"), "湖大制度库");
  assert.equal(formatDiffValue({ topK: 5 }), '{"topK":5}');
  assert.equal(formatDiffValue([1, 2]), "[1,2]");
});

test("差异条数汇总", () => {
  assert.equal(summarizeChangeDiff([]), "无字段变化");
  assert.equal(
    summarizeChangeDiff([
      { field: "/a", before: 1, after: 2 },
      { field: "/b", before: null, after: "x" }
    ]),
    "2 处字段变化"
  );
});

test("业务类型与操作类型展示：已知值转中文，未知值原样输出", () => {
  assert.equal(bizTypeLabel("KNOWLEDGE_BASE"), "知识库");
  assert.equal(bizTypeLabel("INTENT_TREE"), "意图节点");
  assert.equal(bizTypeLabel("UNKNOWN_TYPE"), "UNKNOWN_TYPE");
  assert.equal(bizTypeLabel(null), "-");
  assert.equal(operationTypeLabel("CREATE"), "新增");
  assert.equal(operationTypeLabel("DISABLE"), "禁用");
  assert.equal(operationTypeLabel("OTHER"), "OTHER");
  assert.equal(operationTypeLabel(undefined), "-");
});

test("时间参数转换：datetime-local 补齐秒并替换空格", () => {
  assert.equal(toQueryDateTime("2026-09-26T12:30"), "2026-09-26 12:30:00");
  assert.equal(toQueryDateTime("2026-09-26T12:30:45"), "2026-09-26 12:30:45");
  assert.equal(toQueryDateTime(""), undefined);
  assert.equal(toQueryDateTime(null), undefined);
  assert.equal(toQueryDateTime("   "), undefined);
});

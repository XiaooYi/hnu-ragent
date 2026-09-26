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
  initialAgentStreamState,
  reduceAgentEvent,
  parseToolBlocks,
  summarizeToolArguments,
  stopReasonLabel
} = await loadModule("src/lib/agentStream.ts");

test("事件归约：meta → tool → message → finish 得到完整状态", () => {
  let state = initialAgentStreamState();
  state = reduceAgentEvent(state, "meta", { conversationId: "conv-1", stopReason: "FINAL_ANSWER" });
  assert.equal(state.running, true);
  assert.equal(state.conversationId, "conv-1");

  state = reduceAgentEvent(state, "tool", {
    step: 1,
    toolId: "knowledge_search",
    arguments: { query: "转专业" },
    latencyMs: 12
  });
  assert.equal(state.tools.length, 1);
  assert.equal(state.tools[0].status, "done");

  state = reduceAgentEvent(state, "message", { content: "转专业需要提交申请表" });
  assert.equal(state.answer, "转专业需要提交申请表");

  state = reduceAgentEvent(state, "finish", { reason: "FINAL_ANSWER", elapsedMs: 42 });
  assert.equal(state.running, false);
  assert.equal(state.stopReason, "FINAL_ANSWER");
});

test("hint 与 confirm 事件分别落到提示与待确认调用上", () => {
  let state = initialAgentStreamState();
  state = reduceAgentEvent(state, "hint", { message: "已达到单轮工具调用上限" });
  assert.deepEqual(state.hints, ["已达到单轮工具调用上限"]);

  state = reduceAgentEvent(state, "confirm", {
    toolId: "order_create",
    arguments: { sku: "A" },
    fieldLabels: { sku: "商品编码" },
    stepIndex: 2
  });
  assert.equal(state.confirm?.toolId, "order_create");
  assert.equal(state.confirm?.fieldLabels.sku, "商品编码");
  assert.equal(state.confirm?.stepIndex, 2);

  state = reduceAgentEvent(state, "finish", { reason: "CONFIRM_REQUIRED" });
  assert.equal(stopReasonLabel(state.stopReason), "等待确认");
});

test("未知事件不改变状态，finish 把工具全部收口", () => {
  let state = initialAgentStreamState();
  state = reduceAgentEvent(state, "tool", { toolId: "t", step: 1 });
  const unchanged = reduceAgentEvent(state, "unknown_event", { any: "thing" });
  assert.deepEqual(unchanged, state);

  const finished = reduceAgentEvent(state, "finish", { reason: "MAX_STEPS" });
  assert.equal(finished.tools.every((tool) => tool.status === "done"), true);
  assert.equal(stopReasonLabel(finished.stopReason), "达到工具调用上限");
});

test("历史工具块解析：非法输入返回空数组，字段缺失有兜底", () => {
  assert.deepEqual(parseToolBlocks(null), []);
  assert.deepEqual(parseToolBlocks("not-json"), []);
  assert.deepEqual(parseToolBlocks("{}"), []);

  const rows = parseToolBlocks(
    JSON.stringify([
      { type: "tool", step: 1, toolId: "knowledge_search", arguments: { query: "x" }, observation: "命中", latencyMs: 5 },
      {}
    ])
  );
  assert.equal(rows.length, 2);
  assert.equal(rows[0].toolId, "knowledge_search");
  assert.equal(rows[1].toolId, "unknown");
  assert.equal(rows[1].step, 2);
});

test("参数摘要与结束原因文案", () => {
  assert.equal(summarizeToolArguments(), "无参数");
  assert.equal(summarizeToolArguments({}), "无参数");
  assert.equal(summarizeToolArguments({ query: "转专业", topK: 5 }), "query=转专业，topK=5");

  assert.equal(stopReasonLabel("FINAL_ANSWER"), "回答完成");
  assert.equal(stopReasonLabel("FALLBACK_TEXT"), "直接回答");
  assert.equal(stopReasonLabel("INTERRUPTED"), "已停止");
  assert.equal(stopReasonLabel("FAILED"), "回答失败");
  assert.equal(stopReasonLabel(null), "");
});

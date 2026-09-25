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
  shouldOfferRecommendations,
  resolveRecommendationState,
  initialRecommendationState,
  needsRecommendationFetch
} = await loadModule("src/lib/chatRecommendations.ts");

const assistant = (extra = {}) => ({ id: "m1", role: "assistant", content: "回答", status: "done", ...extra });

test("仅正常完成的助手消息显示推荐入口", () => {
  assert.equal(shouldOfferRecommendations(assistant()), true);
  assert.equal(shouldOfferRecommendations(assistant({ messageStatus: "NORMAL" })), true);
  assert.equal(
    shouldOfferRecommendations(assistant({ messageStatus: "INTERRUPTED" })),
    false,
    "中断的回答不生成推荐"
  );
  assert.equal(shouldOfferRecommendations(assistant({ status: "streaming" })), false);
  assert.equal(shouldOfferRecommendations({ id: "u1", role: "user", content: "问题" }), false);
});

test("接口返回映射为就绪 / 错误两态，EMPTY 视为已就绪的负缓存", () => {
  assert.deepEqual(resolveRecommendationState({ status: "SUCCESS", questions: [" A ", "B", ""] }), {
    state: "ready",
    questions: ["A", "B"]
  });
  assert.deepEqual(resolveRecommendationState({ status: "EMPTY", questions: [] }), {
    state: "ready",
    questions: []
  });
  assert.deepEqual(resolveRecommendationState({ status: "FAILED", questions: [] }), {
    state: "error",
    questions: []
  });
  assert.deepEqual(resolveRecommendationState(null), { state: "error", questions: [] });
});

test("历史消息：null 为未生成、空数组为已就绪（不重复请求）", () => {
  assert.deepEqual(initialRecommendationState(null), { state: "idle", questions: [] });
  assert.deepEqual(initialRecommendationState(undefined), { state: "idle", questions: [] });
  assert.deepEqual(initialRecommendationState([]), { state: "ready", questions: [] });
  assert.deepEqual(initialRecommendationState([" 转专业条件 "]), {
    state: "ready",
    questions: ["转专业条件"]
  });
});

test("仅未生成时才需要请求接口", () => {
  assert.equal(needsRecommendationFetch({ id: "m1", role: "assistant", content: "" }), true);
  assert.equal(
    needsRecommendationFetch({ id: "m1", role: "assistant", content: "", recommendedState: "idle" }),
    true
  );
  assert.equal(
    needsRecommendationFetch({ id: "m1", role: "assistant", content: "", recommendedState: "ready" }),
    false
  );
  assert.equal(
    needsRecommendationFetch({ id: "m1", role: "assistant", content: "", recommendedState: "loading" }),
    false
  );
});

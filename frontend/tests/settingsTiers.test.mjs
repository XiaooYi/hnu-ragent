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

const { tierRows, sortTierNames, formatTierCandidates, formatTierTimeout } = await loadModule(
  "src/lib/settingsTiers.ts"
);

test("档位按 fast → standard → deep 展示，未知档位排在后面并保持接口顺序", () => {
  assert.deepEqual(sortTierNames({ deep: {}, standard: {}, fast: {} }), ["fast", "standard", "deep"]);
  assert.deepEqual(sortTierNames({ slow: {}, fast: {}, turbo: {} }), ["fast", "slow", "turbo"]);
  assert.deepEqual(sortTierNames({}), []);
  assert.deepEqual(sortTierNames(null), []);
  assert.deepEqual(sortTierNames(undefined), []);
});

test("候选按序用箭头连接，超时预算缺失显示 -", () => {
  assert.equal(formatTierCandidates({ candidates: ["qwen3-local", "qwen-plus"] }), "qwen3-local → qwen-plus");
  assert.equal(formatTierCandidates({ candidates: [] }), "-");
  assert.equal(formatTierCandidates(null), "-");
  assert.equal(formatTierTimeout({ timeoutMs: 5000 }), "5000");
  assert.equal(formatTierTimeout({ timeoutMs: null }), "-");
  assert.equal(formatTierTimeout({}), "-");
});

test("档位表行与后端返回一一对应，未配置档位返回空表", () => {
  assert.deepEqual(
    tierRows({
      standard: { candidates: ["qwen3-max", "qwen-plus", "qwen3-local"], timeoutMs: 120000 },
      fast: { candidates: ["qwen3-local", "qwen-plus"], timeoutMs: 5000 }
    }),
    [
      { name: "fast", candidates: "qwen3-local → qwen-plus", timeout: "5000" },
      { name: "standard", candidates: "qwen3-max → qwen-plus → qwen3-local", timeout: "120000" }
    ]
  );
  assert.deepEqual(tierRows(null), []);
});

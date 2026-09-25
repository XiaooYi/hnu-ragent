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
  normalizeCitationMarkers,
  parseCitationIndex,
  buildCitationHref,
  collectSourceIndexes
} = await loadModule("src/lib/chatCitations.ts");

test("标准引用链接可解析出编号", () => {
  assert.equal(parseCitationIndex("#cite-1"), 1);
  assert.equal(parseCitationIndex("#cite-12"), 12);
  assert.equal(parseCitationIndex("https://example.test"), null);
  assert.equal(parseCitationIndex("#cite-0"), null);
  assert.equal(parseCitationIndex(null), null);
  assert.equal(buildCitationHref(3), "#cite-3");
});

test("裸标记按本次来源编号补成链接", () => {
  const content = "转专业需要满足学分要求。[1]\n\n补充：另一份资料【2】也有说明。";

  assert.equal(
    normalizeCitationMarkers(content, [1, 2]),
    "转专业需要满足学分要求。[1](#cite-1)\n\n补充：另一份资料[2](#cite-2)也有说明。"
  );
});

test("不在本次来源内的编号与普通方括号保持不变", () => {
  const content = "见 [待补充] 与 [9] 说明，且 [1] 有效。";

  assert.equal(
    normalizeCitationMarkers(content, [1]),
    "见 [待补充] 与 [9] 说明，且 [1](#cite-1) 有效。"
  );
});

test("代码块与行内代码里的标记不被改写", () => {
  const content = "示例：\n```\n[1] 这不是角标\n```\n行内 `[2]` 也不是，正文 [2] 才是。";

  const normalized = normalizeCitationMarkers(content, [1, 2]);

  assert.ok(normalized.includes("```\n[1] 这不是角标\n```"));
  assert.ok(normalized.includes("行内 `[2]` 也不是"));
  assert.ok(normalized.includes("正文 [2](#cite-2) 才是"));
});

test("没有来源编号时不改写任何内容", () => {
  const content = "正文 [1] 与 [2]。";

  assert.equal(normalizeCitationMarkers(content, []), content);
  assert.deepEqual(collectSourceIndexes(null), []);
  assert.deepEqual(collectSourceIndexes([{ index: 1 }, { index: 2 }]), [1, 2]);
});

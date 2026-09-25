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

const { resolveSourceItems, resolveSourceTitle, resolveSourceTypeLabel } = await loadModule(
  "src/lib/chatSources.ts"
);

test("本地文件来源带出文件类型且没有外链", () => {
  const items = resolveSourceItems([
    { index: 1, docId: "doc-1", docName: "转专业管理办法.pdf", sourceType: "file", fileType: "pdf", excerpt: "申请条件……" }
  ]);

  assert.equal(items.length, 1);
  assert.equal(items[0].title, "转专业管理办法.pdf");
  assert.equal(items[0].typeLabel, "PDF");
  assert.equal(items[0].url, null);
  assert.equal(items[0].openInNewTab, false);
  assert.equal(items[0].excerpt, "申请条件……");
});

test("url/feishu 来源可新窗口打开", () => {
  const items = resolveSourceItems([
    { index: 2, docName: "教务处通知", sourceType: "url", url: "https://www.hnu.edu.cn/notice" }
  ]);

  assert.equal(items[0].url, "https://www.hnu.edu.cn/notice");
  assert.equal(items[0].openInNewTab, true);
  assert.equal(items[0].typeLabel, "URL");
});

test("缺少文件类型时回退来源类型，名称缺失时给占位标题", () => {
  assert.equal(resolveSourceTypeLabel({ index: 3, fileType: ".xlsx" }), "XLSX");
  assert.equal(resolveSourceTypeLabel({ index: 3, sourceType: "feishu" }), "FEISHU");
  assert.equal(resolveSourceTypeLabel({ index: 3 }), "文档");
  assert.equal(resolveSourceTitle({ index: 3 }), "来源 3");
});

test("空来源与非法序号被忽略", () => {
  assert.deepEqual(resolveSourceItems(null), []);
  assert.deepEqual(resolveSourceItems([]), []);
  // @ts-expect-error 故意传入非法序号，验证前端不渲染脏数据
  assert.deepEqual(resolveSourceItems([{ docName: "无序号" }]), []);
});

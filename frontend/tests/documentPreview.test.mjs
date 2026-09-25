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
  resolvePreviewMode,
  needsTextContent,
  parseFrontMatter,
  documentFileUrl,
  isSpreadsheetType,
  isImageType
} = await loadModule("src/lib/documentPreview.ts");

test("按文件类型选择预览方式", () => {
  assert.equal(resolvePreviewMode("pdf"), "pdf");
  assert.equal(resolvePreviewMode("XLSX"), "spreadsheet");
  assert.equal(resolvePreviewMode(".xls"), "spreadsheet");
  assert.equal(resolvePreviewMode("png"), "image");
  assert.equal(resolvePreviewMode("webp"), "image");
  assert.equal(resolvePreviewMode("csv"), "csv");
  assert.equal(resolvePreviewMode("markdown"), "markdown");
  assert.equal(resolvePreviewMode("md"), "markdown");
  assert.equal(resolvePreviewMode("txt"), "text");
  assert.equal(resolvePreviewMode("docx"), "download");
  assert.equal(resolvePreviewMode(null), "download");
});

test("只有文本类预览需要拉取正文", () => {
  assert.equal(needsTextContent("csv"), true);
  assert.equal(needsTextContent("md"), true);
  assert.equal(needsTextContent("pdf"), false);
  assert.equal(needsTextContent("xlsx"), false);
  assert.equal(needsTextContent("png"), false);
});

test("front-matter 单独剥离，正文保留", () => {
  const markdown = "---\nsource: hnu\npage: 3\n---\n\n# 标题\n正文内容";
  const { head, body } = parseFrontMatter(markdown);

  assert.equal(head, "source: hnu\npage: 3");
  assert.equal(body, "\n# 标题\n正文内容");
});

test("没有 front-matter 时原样返回", () => {
  assert.deepEqual(parseFrontMatter("# 标题"), { head: null, body: "# 标题" });
  assert.deepEqual(parseFrontMatter(""), { head: null, body: "" });
});

test("源文件地址拼接不会出现双斜杠", () => {
  assert.equal(documentFileUrl("doc-1", "/api/ragent"), "/api/ragent/knowledge-base/docs/doc-1/file");
  assert.equal(documentFileUrl("doc-1", "/api/ragent/"), "/api/ragent/knowledge-base/docs/doc-1/file");
});

test("类型判定覆盖表格与图片", () => {
  assert.equal(isSpreadsheetType("xlsx"), true);
  assert.equal(isSpreadsheetType("csv"), false);
  assert.equal(isImageType("jpeg"), true);
  assert.equal(isImageType("pdf"), false);
});

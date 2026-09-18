import assert from "node:assert/strict";
import { test } from "node:test";
import { build } from "esbuild";

// Exercise the production TypeScript with an isolated HTTP transport.
const loadModule = async (entry, plugins = []) => {
  const result = await build({ entryPoints: [entry], bundle: true, write: false, format: "esm", platform: "node", tsconfig: "tsconfig.app.json", plugins });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
};
const api = {};
globalThis.__knowledgeUploadTestApi = api;
const { uploadDocuments } = await loadModule("src/services/knowledgeService.ts", [{
  name: "mock-api",
  setup(builder) {
    builder.onResolve({ filter: /^\.\/api$/ }, () => ({ path: "api", namespace: "mock" }));
    builder.onLoad({ filter: /.*/, namespace: "mock" }, () => ({ contents: "export const api = globalThis.__knowledgeUploadTestApi;" }));
  }
}]);
const { buildUploadPayload } = await loadModule("src/lib/knowledgeUpload.ts");
const file = (name) => new File(["content"], name);
const values = {
  sourceType: "file", scheduleEnabled: false, processMode: "chunk", chunkStrategy: "structure_aware",
  chunkSize: "512", overlapSize: "128", targetChars: "1400", maxChars: "1800", minChars: "600", overlapChars: "0",
  tableChunkSize: "900", rowsPerChunk: "25", excelParser: "mineru"
};
const strategies = [{ value: "structure_aware", defaultConfig: { targetChars: 1400, maxChars: 1800, minChars: 600, overlapChars: 0 } }];

test("queue caps concurrency at three, continues after errors and preserves input order", async () => {
  const requests = [];
  let active = 0;
  let maximum = 0;
  api.post = (url, data) => new Promise((resolve, reject) => {
    active++;
    maximum = Math.max(maximum, active);
    requests.push({
      name: data.get("file").name,
      finish(fail = false) {
        active--;
        if (fail) reject(new Error("rejected file"));
        else resolve({ id: data.get("file").name });
      }
    });
  });
  const payloads = Array.from({ length: 7 }, (_, index) => ({ sourceType: "file", file: file(`${index}.txt`) }));
  const events = [];
  const pending = uploadDocuments("kb", payloads, (index, status) => events.push([index, status]));
  assert.equal(requests.length, 3);
  const tick = () => new Promise((resolve) => setImmediate(resolve));
  requests[1].finish(true);
  await tick();
  assert.equal(requests.length, 4);
  requests[2].finish();
  await tick();
  requests[0].finish();
  await tick();
  for (let index = 3; index < 7; index++) {
    requests[index].finish();
    await tick();
  }
  const results = await pending;
  assert.equal(maximum, 3);
  assert.equal(active, 0);
  assert.deepEqual(results.map((result) => result.fileName), payloads.map((payload) => payload.file.name));
  assert.equal(results.filter((result) => result.success).length, 6);
  assert.equal(results[1].message, "rejected file");
  assert.equal(events.filter(([, status]) => status === "uploading").length, 7);
  assert.equal(events.filter(([, status]) => status === "failed").length, 1);

  const retried = [];
  api.post = async (url, data) => { retried.push(data.get("file").name); return { id: "retry" }; };
  await uploadDocuments("kb", payloads.filter((_, index) => !results[index].success));
  assert.deepEqual(retried, ["1.txt"]);
});

test("empty batch makes no requests", async () => {
  api.post = () => assert.fail("unexpected request");
  assert.deepEqual(await uploadDocuments("kb", []), []);
});

test("mixed documents produce independent text, CSV and Excel configuration", () => {
  const text = buildUploadPayload(file("guide.md"), values, strategies);
  const csv = buildUploadPayload(file("table.CSV"), values, strategies);
  const excel = buildUploadPayload(file("table.xlsx"), values, strategies);
  assert.equal(text.chunkStrategy, "structure_aware");
  assert.deepEqual(JSON.parse(text.chunkConfig), { targetChars: 1400, maxChars: 1800, minChars: 600, overlapChars: 0 });
  assert.equal(csv.chunkStrategy, "fixed_size");
  assert.deepEqual(JSON.parse(csv.chunkConfig), { chunkSize: 900, overlapSize: 0, rowsPerChunk: 25 });
  assert.deepEqual(JSON.parse(excel.chunkConfig), { chunkSize: 900, overlapSize: 0, rowsPerChunk: 25, excelParser: "mineru" });
});

test("pipeline mode applies to both text and tables without chunk settings", () => {
  for (const name of ["guide.md", "table.xls"]) {
    const payload = buildUploadPayload(file(name), { ...values, processMode: "pipeline", pipelineId: "pipeline" }, []);
    assert.equal(payload.pipelineId, "pipeline");
    assert.equal(payload.chunkStrategy, undefined);
    assert.equal(payload.chunkConfig, null);
  }
});

test("URL uploads preserve scheduling and use a single request without a file", async () => {
  const payload = buildUploadPayload(null, {
    ...values, sourceType: "url", sourceLocation: " https://example.com/guide.md ",
    scheduleEnabled: true, scheduleCron: " 0 0 0 * * ? "
  }, strategies);
  let requests = 0;
  api.post = async (url, data) => {
    requests++;
    assert.equal(data.has("file"), false);
    assert.equal(data.get("sourceLocation"), "https://example.com/guide.md");
    assert.equal(data.get("scheduleEnabled"), "true");
    assert.equal(data.get("scheduleCron"), "0 0 0 * * ?");
    return { id: "url-document" };
  };
  assert.equal((await uploadDocuments("kb", [payload]))[0].success, true);
  assert.equal(requests, 1);
});

test("missing text strategies stop submission rather than silently dropping configuration", () => {
  assert.throws(() => buildUploadPayload(file("guide.md"), values, []));
});

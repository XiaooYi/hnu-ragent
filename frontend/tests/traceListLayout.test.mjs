import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import postcss from "postcss";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const stylesheetPath = path.resolve(testDirectory, "../src/styles/globals.css");

async function declarationsFor(selector) {
  const stylesheet = await readFile(stylesheetPath, "utf8");
  const root = postcss.parse(stylesheet);
  const declarations = [];

  root.walkRules((rule) => {
    if (rule.selector === selector) {
      rule.walkDecls((declaration) => declarations.push([declaration.prop, declaration.value]));
    }
  });

  return new Map(declarations);
}

test("trace list keeps record rows scrollable inside the fixed desktop workspace", async () => {
  const tableWrap = await declarationsFor(".admin-layout .trace-list-table-wrap");

  assert.equal(tableWrap.get("overflow-y"), "auto");
});

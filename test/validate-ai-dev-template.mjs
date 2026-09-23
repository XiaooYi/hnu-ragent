import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { constants } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL("..", import.meta.url)));

const requiredFiles = [
  ["CONTEXT.md", /Ragent/],
  ["CONTEXT.md", /MultiChannelRetrievalEngine/],
  ["docs/rules/README.md", /不可随意改变/],
  ["docs/rules/retrieval-invariants.md", /TopK/],
  ["docs/database/README.md", /resources\/database/],
  ["docs/examples/README.md", /Ragent/],
  ["test/test-cases.md", /RAG-001/],
];

for (const [relativePath, pattern] of requiredFiles) {
  const fullPath = join(root, relativePath);
  await access(fullPath, constants.R_OK);
  const content = await readFile(fullPath, "utf8");
  assert.match(content, pattern, `${relativePath} 缺少 Ragent AI 开发模板约定`);
}

const applicationYaml = await readFile(
  join(root, "bootstrap/src/main/resources/application.yaml"),
  "utf8",
);
assert.match(applicationYaml, /rag:/, "Ragent 配置入口缺少 rag 根节点");
assert.match(applicationYaml, /ai:/, "Ragent 配置入口缺少 ai 根节点");

const compose = await readFile(join(root, "deploy/compose.yaml"), "utf8");
assert.match(compose, /resources\/database\/schema_pg\.sql/, "部署配置未使用 Ragent 数据库初始化脚本");

console.log(`AI 开发模板验证通过：检查 ${requiredFiles.length} 份模板文档与现有项目入口`);

import assert from "node:assert/strict";
import { access, readdir, readFile } from "node:fs/promises";
import { constants } from "node:fs";
import { dirname, join, normalize, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const requiredDocuments = [
  "docs/README.md",
  "docs/architecture/ragent-architecture.md",
  "docs/architecture/multi-channel-retrieval.md",
  "docs/development/document-table-cleaning-chunking.md",
  "docs/development/git-commit-convention.md",
  "docs/operations/project-startup-guide.md",
  "docs/operations/docker-production-deployment.md",
  "docs/operations/github-actions-auto-deployment.md",
  "docs/operations/tencent-cloud-access.md",
  "docs/domain/enterprise-internal-mcp-tools.md",
  "docs/evaluation/rag-online-questions-answers.md",
  "docs/evaluation/ragent-eval-docs-interview-qa.md",
  "docs/evaluation/ragent-interview-20-answers.md",
  "docs/examples/pdf/pdf-ingestion-example.md",
  "docs/archive/README.md",
];
const requiredDirectories = [
  "docs/architecture",
  "docs/development",
  "docs/operations",
  "docs/domain",
  "docs/evaluation",
  "docs/rules",
  "docs/database",
  "docs/examples",
  "docs/archive",
];

for (const document of requiredDocuments) {
  await access(join(root, document), constants.R_OK);
}

for (const directory of requiredDirectories) {
  await access(join(root, directory), constants.R_OK);
}

async function markdownFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return markdownFiles(path);
    return entry.isFile() && entry.name.endsWith(".md") ? [path] : [];
  }));
  return nested.flat();
}

const files = await markdownFiles(join(root, "docs"));
const brokenLinks = [];
for (const file of files) {
  const content = await readFile(file, "utf8");
  for (const match of content.matchAll(/\]\(([^)#]+)(?:#[^)]*)?\)/g)) {
    const link = match[1];
    if (/^(https?:|mailto:)/i.test(link)) continue;
    const target = normalize(join(dirname(file), link));
    try {
      await access(target, constants.R_OK);
    } catch {
      brokenLinks.push(`${relative(root, file)} -> ${link}`);
    }
  }
}

assert.deepEqual(brokenLinks, [], `发现断开的本地文档链接:\n${brokenLinks.join("\n")}`);
console.log(`文档结构验证通过：检查 ${requiredDirectories.length} 个目录、${requiredDocuments.length} 份权威文档和 ${files.length} 份 Markdown 文档`);
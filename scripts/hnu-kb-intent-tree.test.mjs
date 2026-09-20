import assert from "node:assert/strict";
import test from "node:test";

import {
  buildHnuKbIntentPlan,
  createIntentTreeImporter,
  resolveKnowledgeBaseIds
} from "./hnu-kb-intent-tree.mjs";

const KNOWLEDGE_BASES = [
  { id: "kb-life", name: "湖大通用概况与校园生活" },
  { id: "kb-academic", name: "湖大本科教学与学业制度" },
  { id: "kb-training", name: "湖大本科专业培养方案" },
  { id: "kb-aid", name: "湖大奖助学金资助" },
  { id: "kb-graduate", name: "湖大研究生新生与研究生管理" }
];

test("buildHnuKbIntentPlan creates only KB nodes and binds every topic to its named knowledge base", () => {
  const plan = buildHnuKbIntentPlan();

  assert.equal(plan[0].intentCode, "hnu-campus-knowledge");
  assert.equal(plan[0].level, 0);
  assert.equal(plan[0].kind, 0);
  assert.equal(plan.filter((node) => node.level === 1).length, 5);

  const topics = plan.filter((node) => node.level === 2);
  assert.ok(topics.length >= 20);
  assert.ok(topics.every((node) => node.kind === 0 && node.kbName));
  assert.ok(topics.every((node) => node.parentCode));
  assert.ok(topics.every((node) => node.topK === 5));
});

test("resolveKnowledgeBaseIds uses exact names and rejects an incomplete inventory", () => {
  const ids = resolveKnowledgeBaseIds(KNOWLEDGE_BASES);
  assert.equal(ids.get("湖大本科专业培养方案"), "kb-training");

  assert.throws(
    () => resolveKnowledgeBaseIds(KNOWLEDGE_BASES.slice(0, 4)),
    /缺少知识库：湖大研究生新生与研究生管理/
  );
});

test("createIntentTreeImporter creates missing nodes in parent-first order and is idempotent", async () => {
  const requests = [];
  const importer = createIntentTreeImporter({
    request: async ({ method, path, body }) => {
      requests.push({ method, path, body });
      if (path === "/knowledge-base?current=1&size=100") return { records: KNOWLEDGE_BASES, pages: 1 };
      if (path === "/intent-tree/trees") return [];
      if (method === "POST" && path === "/intent-tree") return String(requests.length);
      throw new Error(`Unexpected request: ${method} ${path}`);
    }
  });

  const first = await importer.apply();
  const created = requests.filter((request) => request.method === "POST");
  assert.equal(first.created.length, buildHnuKbIntentPlan().length);
  assert.equal(created[0].body.intentCode, "hnu-campus-knowledge");
  assert.equal(created[1].body.level, 1);
  assert.equal(created.at(-1).body.level, 2);

  const existingTree = toTree(buildHnuKbIntentPlan().map((node, index) => ({ ...node, id: String(index + 1) })));
  const idempotentImporter = createIntentTreeImporter({
    request: async ({ method, path }) => {
      if (path === "/knowledge-base?current=1&size=100") return { records: KNOWLEDGE_BASES, pages: 1 };
      if (path === "/intent-tree/trees") return existingTree;
      throw new Error(`Unexpected write: ${method} ${path}`);
    }
  });
  const second = await idempotentImporter.apply();
  assert.deepEqual(second.created, []);
  assert.equal(second.skipped.length, buildHnuKbIntentPlan().length);
});

function toTree(nodes) {
  const byCode = new Map(nodes.map((node) => [node.intentCode, { ...node, children: [] }]));
  const roots = [];
  for (const node of byCode.values()) {
    if (node.parentCode) byCode.get(node.parentCode).children.push(node);
    else roots.push(node);
  }
  return roots;
}

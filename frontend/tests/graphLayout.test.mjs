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
  buildTypeColorMap,
  normalizeType,
  layoutGraph,
  filterGraphByKeyword,
  graphStats,
  describeNode,
  TYPE_PALETTE
} = await loadModule("src/lib/graphLayout.ts");

const graph = {
  truncated: false,
  nodes: [
    { id: "n1", name: "教务处", type: "机构" },
    { id: "n2", name: "学籍科", type: "机构" },
    { id: "n3", name: "转专业", type: "事项" },
    { id: "n4", name: "孤立节点" }
  ],
  edges: [
    { id: "e1", source: "n1", target: "n2", label: "下设" },
    { id: "e2", source: "n2", target: "n3", label: "负责" }
  ]
};

test("类型配色按首次出现顺序分配，无类型归入未分类", () => {
  const colors = buildTypeColorMap(graph.nodes);
  assert.equal(colors["机构"], TYPE_PALETTE[0]);
  assert.equal(colors["事项"], TYPE_PALETTE[1]);
  assert.equal(colors["未分类"], TYPE_PALETTE[2]);
  assert.equal(normalizeType(null), "未分类");
  assert.equal(normalizeType("  "), "未分类");
  assert.equal(normalizeType(" 机构 "), "机构");
});

test("布局结果与节点一一对应，坐标落在画布内且可复现", () => {
  const first = layoutGraph(graph.nodes, graph.edges, 900, 560);
  const second = layoutGraph(graph.nodes, graph.edges, 900, 560);

  assert.equal(first.length, graph.nodes.length);
  assert.deepEqual(
    first.map((node) => node.id),
    graph.nodes.map((node) => node.id)
  );
  for (const node of first) {
    assert.ok(Number.isFinite(node.x) && Number.isFinite(node.y), `${node.id} 坐标必须是有限数`);
    assert.ok(node.x >= 24 && node.x <= 876, `${node.id} x 越界: ${node.x}`);
    assert.ok(node.y >= 24 && node.y <= 536, `${node.id} y 越界: ${node.y}`);
  }
  assert.deepEqual(first, second, "同一输入必须得到同一布局");
  assert.equal(layoutGraph([], [], 900, 560).length, 0);
});

test("关键词过滤保留命中节点及其一跳邻居，同时剔除悬空边", () => {
  const focused = filterGraphByKeyword(graph, "教务处");
  assert.deepEqual(
    focused.nodes.map((node) => node.id),
    ["n1", "n2"]
  );
  assert.deepEqual(
    focused.edges.map((edge) => edge.id),
    ["e1"]
  );

  assert.equal(filterGraphByKeyword(graph, "  ").nodes.length, graph.nodes.length, "空关键词不过滤");
  assert.deepEqual(filterGraphByKeyword(graph, "不存在的实体"), { nodes: [], edges: [], truncated: false });
});

test("统计与节点描述", () => {
  assert.deepEqual(graphStats(graph), { nodeCount: 4, edgeCount: 2, typeCount: 3 });
  assert.deepEqual(graphStats({ nodes: [], edges: [], truncated: false }), {
    nodeCount: 0,
    edgeCount: 0,
    typeCount: 0
  });
  assert.equal(describeNode({ id: "n1", name: "教务处", type: "机构", description: "负责学籍" }), "负责学籍");
  assert.equal(describeNode({ id: "n1", name: "教务处", type: "机构" }), "机构 · 暂无描述");
  assert.equal(describeNode(null), "-");
});

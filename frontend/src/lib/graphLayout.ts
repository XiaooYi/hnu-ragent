/**
 * 知识图谱可视化的布局与着色逻辑。
 *
 * 后端只返回节点 / 边与截断标记，前端负责把图放到画布上。这里刻意不引入图可视化库：
 * 校内环境离线构建，自绘 SVG 已能覆盖「看结构 + 查实体 + 点开详情」的需求，且布局可单测。
 */

export interface GraphNode {
  id: string;
  name: string;
  type?: string | null;
  description?: string | null;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  label?: string | null;
  description?: string | null;
}

export interface GraphView {
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
}

export interface PositionedNode extends GraphNode {
  x: number;
  y: number;
  color: string;
}

export const TYPE_PALETTE = [
  "#5B8FF9",
  "#61DDAA",
  "#F6BD16",
  "#7262FD",
  "#78D3F8",
  "#F08BB4",
  "#FF9845",
  "#9661BC"
];

const UNKNOWN_TYPE = "未分类";

/**
 * 按类型首次出现顺序分配配色，超出调色板后循环取色；无类型归入「未分类」
 */
export function buildTypeColorMap(nodes: GraphNode[]): Record<string, string> {
  const map: Record<string, string> = {};
  let index = 0;
  for (const node of nodes) {
    const type = normalizeType(node.type);
    if (!(type in map)) {
      map[type] = TYPE_PALETTE[index % TYPE_PALETTE.length];
      index += 1;
    }
  }
  return map;
}

export function normalizeType(type?: string | null): string {
  const trimmed = (type ?? "").trim();
  return trimmed.length > 0 ? trimmed : UNKNOWN_TYPE;
}

/**
 * 极简力导向布局：斥力 + 弹簧 + 阻尼，迭代次数固定，保证同一输入得到同一布局（可回归测试）
 */
export function layoutGraph(
  nodes: GraphNode[],
  edges: GraphEdge[],
  width = 900,
  height = 560,
  iterations = 220
): PositionedNode[] {
  if (nodes.length === 0) return [];
  const colorMap = buildTypeColorMap(nodes);
  const centerX = width / 2;
  const centerY = height / 2;
  const radius = Math.min(width, height) / 2 - 40;

  // 初始位置放在圆周上：确定性、且不会出现「所有点重合导致斥力方向为 0」的退化解
  const positions = nodes.map((node, index) => {
    const angle = (2 * Math.PI * index) / nodes.length;
    return {
      node,
      x: centerX + radius * Math.cos(angle),
      y: centerY + radius * Math.sin(angle)
    };
  });
  const indexById = new Map(positions.map((item, index) => [item.node.id, index]));
  const links = edges
    .map((edge) => ({ source: indexById.get(edge.source), target: indexById.get(edge.target) }))
    .filter((link) => link.source !== undefined && link.target !== undefined) as {
    source: number;
    target: number;
  }[];

  const area = width * height;
  const k = Math.sqrt(area / Math.max(nodes.length, 1));
  let temperature = Math.min(width, height) / 8;

  for (let step = 0; step < iterations; step += 1) {
    const dispX = new Array(positions.length).fill(0);
    const dispY = new Array(positions.length).fill(0);

    for (let i = 0; i < positions.length; i += 1) {
      for (let j = i + 1; j < positions.length; j += 1) {
        let dx = positions[i].x - positions[j].x;
        let dy = positions[i].y - positions[j].y;
        let distance = Math.hypot(dx, dy);
        if (distance < 0.01) {
          dx = 0.01;
          dy = 0.01;
          distance = 0.01;
        }
        const force = (k * k) / distance;
        const fx = (dx / distance) * force;
        const fy = (dy / distance) * force;
        dispX[i] += fx;
        dispY[i] += fy;
        dispX[j] -= fx;
        dispY[j] -= fy;
      }
    }

    for (const link of links) {
      const dx = positions[link.source].x - positions[link.target].x;
      const dy = positions[link.source].y - positions[link.target].y;
      const distance = Math.max(Math.hypot(dx, dy), 0.01);
      const force = (distance * distance) / k;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;
      dispX[link.source] -= fx;
      dispY[link.source] -= fy;
      dispX[link.target] += fx;
      dispY[link.target] += fy;
    }

    for (let i = 0; i < positions.length; i += 1) {
      const length = Math.max(Math.hypot(dispX[i], dispY[i]), 0.01);
      const limited = Math.min(length, temperature);
      positions[i].x += (dispX[i] / length) * limited;
      positions[i].y += (dispY[i] / length) * limited;
      // 收敛到画布内并留出节点半径，避免渲染时被裁掉
      positions[i].x = clamp(positions[i].x, 24, width - 24);
      positions[i].y = clamp(positions[i].y, 24, height - 24);
    }
    temperature = Math.max(temperature * 0.96, 0.5);
  }

  return positions.map((item) => ({
    ...item.node,
    x: round(item.x),
    y: round(item.y),
    color: colorMap[normalizeType(item.node.type)] ?? TYPE_PALETTE[0]
  }));
}

/**
 * 关键词过滤：命中节点及其一跳邻居保留，用于「搜索实体后聚焦子图」
 */
export function filterGraphByKeyword(graph: GraphView, keyword: string): GraphView {
  const needle = keyword.trim().toLowerCase();
  if (!needle) return graph;

  const hit = new Set(
    graph.nodes
      .filter((node) => node.name.toLowerCase().includes(needle) || node.id.toLowerCase().includes(needle))
      .map((node) => node.id)
  );
  if (hit.size === 0) return { nodes: [], edges: [], truncated: graph.truncated };

  const neighbors = new Set(hit);
  for (const edge of graph.edges) {
    if (hit.has(edge.source)) neighbors.add(edge.target);
    if (hit.has(edge.target)) neighbors.add(edge.source);
  }
  const nodes = graph.nodes.filter((node) => neighbors.has(node.id));
  const kept = new Set(nodes.map((node) => node.id));
  return {
    nodes,
    edges: graph.edges.filter((edge) => kept.has(edge.source) && kept.has(edge.target)),
    truncated: graph.truncated
  };
}

export function graphStats(graph: GraphView): { nodeCount: number; edgeCount: number; typeCount: number } {
  const types = new Set(graph.nodes.map((node) => normalizeType(node.type)));
  return {
    nodeCount: graph.nodes.length,
    edgeCount: graph.edges.length,
    typeCount: graph.nodes.length === 0 ? 0 : types.size
  };
}

export function describeNode(node?: GraphNode | null): string {
  if (!node) return "-";
  const type = normalizeType(node.type);
  return node.description && node.description.trim().length > 0 ? node.description.trim() : `${type} · 暂无描述`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

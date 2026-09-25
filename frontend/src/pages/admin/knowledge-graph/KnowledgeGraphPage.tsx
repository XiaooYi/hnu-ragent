import { useEffect, useMemo, useState } from "react";
import { Library, Minus, Plus, RefreshCw, Search, Share2 } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  buildTypeColorMap,
  describeNode,
  filterGraphByKeyword,
  graphStats,
  layoutGraph,
  normalizeType,
  type GraphNode,
  type GraphView
} from "@/lib/graphLayout";
import { getKnowledgeGraph } from "@/services/knowledgeGraphService";
import { getKnowledgeBases, getDocuments, type KnowledgeBase, type KnowledgeDocument } from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";

const ALL = "ALL";
const CANVAS_WIDTH = 960;
const CANVAS_HEIGHT = 620;

export function KnowledgeGraphPage() {
  const [graph, setGraph] = useState<GraphView | null>(null);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [focusEntity, setFocusEntity] = useState("");
  const [depth, setDepth] = useState("2");
  const [zoom, setZoom] = useState(1);
  const [selected, setSelected] = useState<GraphNode | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [kbId, setKbId] = useState(ALL);
  const [docId, setDocId] = useState(ALL);

  const loadGraph = async (entity = focusEntity, nextDepth = depth, nextKb = kbId, nextDoc = docId) => {
    setLoading(true);
    try {
      const selectedKb = knowledgeBases.find((item) => item.id === nextKb);
      const data = await getKnowledgeGraph({
        entity: entity.trim() || undefined,
        collection: selectedKb?.collectionName || undefined,
        doc: nextDoc === ALL ? undefined : nextDoc,
        depth: Number(nextDepth) || 2,
        limit: 200
      });
      setGraph(data);
      setSelected(null);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识图谱失败（请确认 rag.graph.type=lightrag）"));
      console.error(error);
      setGraph(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    getKnowledgeBases(1, 100)
      .then((items) => setKnowledgeBases(items || []))
      .catch((error) => console.error(error));
  }, []);

  useEffect(() => {
    if (kbId === ALL) {
      setDocuments([]);
      setDocId(ALL);
      return;
    }
    getDocuments(kbId, { current: 1, size: 200 })
      .then((items) => setDocuments(items || []))
      .catch((error) => console.error(error));
  }, [kbId]);

  const visibleGraph = useMemo(
    () => (graph ? filterGraphByKeyword(graph, keyword) : { nodes: [], edges: [], truncated: false }),
    [graph, keyword]
  );

  const positioned = useMemo(
    () => layoutGraph(visibleGraph.nodes, visibleGraph.edges, CANVAS_WIDTH, CANVAS_HEIGHT),
    [visibleGraph]
  );

  const positionById = useMemo(() => new Map(positioned.map((node) => [node.id, node])), [positioned]);
  const colorMap = useMemo(() => buildTypeColorMap(visibleGraph.nodes), [visibleGraph.nodes]);
  const stats = graphStats(visibleGraph);

  const handleSearch = () => {
    setFocusEntity(keyword.trim());
    loadGraph(keyword.trim());
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">知识图谱</h1>
          <p className="admin-page-subtitle">
            实体与关系视图（来源：LightRAG）；仅当 rag.graph.type=lightrag 时可用
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => loadGraph()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="space-y-4 pt-6">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
            <Select
              value={kbId}
              onValueChange={(value) => {
                setKbId(value);
                setDocId(ALL);
              }}
            >
              <SelectTrigger aria-label="知识库">
                <SelectValue placeholder="全部知识库" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部知识库</SelectItem>
                {knowledgeBases.map((kb) => (
                  <SelectItem key={kb.id} value={kb.id}>
                    {kb.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Select value={docId} onValueChange={setDocId} disabled={documents.length === 0}>
              <SelectTrigger aria-label="文档">
                <SelectValue placeholder="全部文档" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部文档</SelectItem>
                {documents.map((doc) => (
                  <SelectItem key={doc.id} value={doc.id}>
                    {doc.docName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Select value={depth} onValueChange={setDepth}>
              <SelectTrigger aria-label="子图深度">
                <SelectValue placeholder="深度" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="1">深度 1 跳</SelectItem>
                <SelectItem value="2">深度 2 跳</SelectItem>
                <SelectItem value="3">深度 3 跳</SelectItem>
              </SelectContent>
            </Select>

            <Input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onKeyDown={(event) => event.key === "Enter" && handleSearch()}
              placeholder="按实体名搜索（回车聚焦）"
            />

            <div className="flex gap-2">
              <Button variant="outline" onClick={handleSearch}>
                <Search className="mr-2 h-4 w-4" />
                加载
              </Button>
              <Button
                variant="ghost"
                onClick={() => {
                  setKeyword("");
                  setFocusEntity("");
                  loadGraph("", depth);
                }}
              >
                重置筛选
              </Button>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            <Badge variant="secondary">
              <Library className="mr-1 h-3 w-3" />
              节点 {stats.nodeCount}
            </Badge>
            <Badge variant="secondary">
              <Share2 className="mr-1 h-3 w-3" />
              关系 {stats.edgeCount}
            </Badge>
            <Badge variant="secondary">实体类型 {stats.typeCount}</Badge>
            {visibleGraph.truncated ? <Badge variant="destructive">已按节点上限截断</Badge> : null}
            <div className="ml-auto flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => setZoom((prev) => Math.max(0.4, prev - 0.1))}>
                <Minus className="h-4 w-4" />
              </Button>
              <span className="tabular-nums">{Math.round(zoom * 100)}%</span>
              <Button variant="outline" size="sm" onClick={() => setZoom((prev) => Math.min(2, prev + 0.1))}>
                <Plus className="h-4 w-4" />
              </Button>
            </div>
          </div>

          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
            <div className="overflow-hidden rounded-lg border bg-slate-50">
              {loading ? (
                <div className="flex h-[420px] items-center justify-center text-muted-foreground">加载中...</div>
              ) : positioned.length === 0 ? (
                <div className="flex h-[420px] items-center justify-center text-muted-foreground">
                  暂无图谱数据（图谱未启用、文档尚未同步，或筛选后无匹配实体）
                </div>
              ) : (
                <svg
                  viewBox={`0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}`}
                  style={{ width: "100%", height: `${Math.round(CANVAS_HEIGHT * zoom)}px` }}
                  role="img"
                  aria-label="知识图谱"
                >
                  {visibleGraph.edges.map((edge) => {
                    const source = positionById.get(edge.source);
                    const target = positionById.get(edge.target);
                    if (!source || !target) return null;
                    return (
                      <g key={edge.id}>
                        <line
                          x1={source.x}
                          y1={source.y}
                          x2={target.x}
                          y2={target.y}
                          stroke="#cbd5e1"
                          strokeWidth={1.2}
                        />
                        {zoom >= 1 ? (
                          <text
                            x={(source.x + target.x) / 2}
                            y={(source.y + target.y) / 2}
                            fontSize={9}
                            fill="#94a3b8"
                            textAnchor="middle"
                          >
                            {edge.label || ""}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                  {positioned.map((node) => (
                    <g key={node.id} onClick={() => setSelected(node)} style={{ cursor: "pointer" }}>
                      <circle
                        cx={node.x}
                        cy={node.y}
                        r={selected?.id === node.id ? 15 : 11}
                        fill={node.color}
                        stroke={selected?.id === node.id ? "#0f172a" : "#ffffff"}
                        strokeWidth={selected?.id === node.id ? 2.5 : 1.5}
                      />
                      <text x={node.x} y={node.y + 26} fontSize={11} fill="#334155" textAnchor="middle">
                        {node.name.length > 12 ? `${node.name.slice(0, 12)}…` : node.name}
                      </text>
                    </g>
                  ))}
                </svg>
              )}
            </div>

            <div className="space-y-3">
              <div className="rounded-lg border px-4 py-3">
                <div className="text-xs text-muted-foreground">实体类型图例</div>
                <div className="mt-2 space-y-1">
                  {Object.entries(colorMap).map(([type, color]) => (
                    <div key={type} className="flex items-center gap-2 text-sm">
                      <span className="inline-block h-3 w-3 rounded-full" style={{ backgroundColor: color }} />
                      <span>{type}</span>
                    </div>
                  ))}
                  {Object.keys(colorMap).length === 0 ? <span className="text-sm text-muted-foreground">-</span> : null}
                </div>
              </div>

              <div className="rounded-lg border px-4 py-3">
                <div className="text-xs text-muted-foreground">节点详情</div>
                {selected ? (
                  <div className="mt-2 space-y-2 text-sm">
                    <div className="font-medium">{selected.name}</div>
                    <Badge variant="secondary">{normalizeType(selected.type)}</Badge>
                    <p className="text-muted-foreground">{describeNode(selected)}</p>
                    <div className="break-all text-xs text-muted-foreground">ID：{selected.id}</div>
                  </div>
                ) : (
                  <div className="mt-2 text-sm text-muted-foreground">点击图中节点查看详情</div>
                )}
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

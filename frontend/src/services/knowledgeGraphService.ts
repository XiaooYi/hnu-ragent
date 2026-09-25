import { api } from "@/services/api";

import type { GraphView } from "@/lib/graphLayout";

export interface GraphViewQuery {
  entity?: string;
  collection?: string;
  doc?: string;
  depth?: number;
  limit?: number;
}

export async function getKnowledgeGraph(query: GraphViewQuery): Promise<GraphView> {
  return api.get<GraphView, GraphView>("/graph/view", { params: query });
}

export async function searchGraphEntities(keyword?: string, limit = 20): Promise<string[]> {
  return api.get<string[], string[]>("/graph/entities", {
    params: { keyword: keyword || undefined, limit }
  });
}

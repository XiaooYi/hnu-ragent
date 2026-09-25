/**
 * 系统设置页「能力开关 / 检索管线 / 后端选型」的展示逻辑。
 *
 * 这些参数直接决定检索结果，页面只做只读呈现：所有取值都来自后端返回的生效配置，
 * 前端不重算阈值，只在「未配置回退」这类语义上按后端同一规则展示（如召回预算）。
 */

import type { BackendSettings, FeatureSettings, SearchSettings } from "@/services/settingsService";

export interface LabeledFlag {
  key: string;
  label: string;
  enabled: boolean;
  hint: string;
}

const FEATURE_HINTS: Record<string, { label: string; hint: string }> = {
  queryRewrite: { label: "查询改写", hint: "改写并拆分子问题，提升检索命中率" },
  rerank: { label: "Rerank 精排", hint: "关闭后无 TopK 截断点，召回结果整体进上下文" },
  citation: { label: "行内引用角标", hint: "为资料注入编号并要求模型输出 [N](#cite-N)" },
  contextEnrich: { label: "上下文富化", hint: "回表补齐文档归属并按文档聚合渲染" },
  trace: { label: "链路追踪", hint: "记录 t_rag_trace_run / node 全链路节点" }
};

export function featureFlags(features?: FeatureSettings | null): LabeledFlag[] {
  if (!features) return [];
  return Object.keys(FEATURE_HINTS)
    .filter((key) => key in features)
    .map((key) => ({
      key,
      label: FEATURE_HINTS[key].label,
      hint: FEATURE_HINTS[key].hint,
      enabled: features[key as keyof FeatureSettings] === true
    }));
}

export interface ChannelRow {
  name: string;
  enabled: string;
  detail: string;
}

const formatNumber = (value?: number | null): string => (value === null || value === undefined ? "-" : String(value));

export function channelRows(search?: SearchSettings | null): ChannelRow[] {
  if (!search?.channels) return [];
  const channels = search.channels;
  const rows: ChannelRow[] = [];

  if (channels.vectorGlobal) {
    rows.push({
      name: "向量全局检索",
      enabled: channels.vectorGlobal.enabled === false ? "关闭" : "启用",
      detail: `意图置信度 < ${formatNumber(channels.vectorGlobal.confidenceThreshold)} 时补全库检索；单意图分 < ${formatNumber(
        channels.vectorGlobal.singleIntentSupplementThreshold
      )} 时同样补检`
    });
  }
  if (channels.intentDirected) {
    rows.push({
      name: "意图定向检索",
      enabled: channels.intentDirected.enabled === false ? "关闭" : "启用",
      detail: `只检索意图分 ≥ ${formatNumber(channels.intentDirected.minIntentScore)} 的知识库`
    });
  }
  if (channels.keyword) {
    rows.push({
      name: "关键词检索",
      enabled: channels.keyword.enabled === true ? "启用" : "关闭",
      detail: `检索范围：${channels.keyword.mode || "-"}（需 rag.keyword.type=es 才可用）`
    });
  }
  return rows;
}

export function pipelineRows(search?: SearchSettings | null): { label: string; value: string }[] {
  if (!search) return [];
  return [
    { label: "进入上下文条数（TopK）", value: formatNumber(search.defaultTopK) },
    { label: "召回预算（通道取数深度）", value: formatNumber(resolveRecallBudget(search)) },
    { label: "融合策略", value: search.fusion?.strategy || "-" },
    { label: "RRF 平滑常数 k", value: formatNumber(search.fusion?.rrfK) },
    { label: "精排候选池上限", value: formatNumber(search.fusion?.rerankCandidateLimit) },
    { label: "证据闸门最低精排分", value: formatNumber(search.evidence?.minRerankScore) },
    {
      label: "单通道超时",
      value: search.channels?.timeoutMs ? `${search.channels.timeoutMs} ms` : "-"
    }
  ];
}

/**
 * 召回预算展示口径与后端一致：显式配置 > 0 时用配置值，否则跟随精排候选池上限
 */
export function resolveRecallBudget(search?: SearchSettings | null): number | null {
  if (!search) return null;
  const configured = search.recallBudget ?? 0;
  if (configured > 0) return configured;
  return search.fusion?.rerankCandidateLimit ?? null;
}

export function backendRows(backends?: BackendSettings | null): { label: string; value: string }[] {
  if (!backends) return [];
  const keyword = backends.keyword;
  return [
    { label: "向量后端", value: backends.vector?.type || "-" },
    { label: "关键词后端", value: keyword?.type || "-" },
    { label: "关键词索引", value: keyword?.index || "-" },
    { label: "关键词服务地址", value: keyword?.uris || "-" },
    {
      label: "关键词分词器",
      value: keyword?.analyzer ? `${keyword.analyzer} / ${keyword.searchAnalyzer || "-"}` : "-"
    },
    { label: "文件存储平台", value: backends.storage?.platform || "-" },
    { label: "文件存储地址", value: backends.storage?.endpoint || "-" }
  ];
}

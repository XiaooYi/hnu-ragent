/**
 * 变更审计日志的展示逻辑。
 *
 * 后端在写库时就算好了字段级差异（JSON Pointer 路径 + before/after），这里只做解析与展示，
 * 不在前端重算差异，保证页面看到的与审计表里存的一致。
 */

export interface ChangeDiffRow {
  field: string;
  before: unknown;
  after: unknown;
}

const BIZ_TYPE_LABELS: Record<string, string> = {
  KNOWLEDGE_BASE: "知识库",
  KNOWLEDGE_DOCUMENT: "知识库文档",
  KNOWLEDGE_CHUNK: "文档分块",
  INGESTION_PIPELINE: "流水线",
  INGESTION_TASK: "流水线任务",
  INTENT_TREE: "意图节点",
  QUERY_TERM_MAPPING: "关键词映射",
  SAMPLE_QUESTION: "示例问题",
  USER: "用户"
};

const OPERATION_TYPE_LABELS: Record<string, string> = {
  CREATE: "新增",
  UPDATE: "更新",
  DELETE: "删除",
  ENABLE: "启用",
  DISABLE: "禁用",
  RUN: "执行"
};

export const BIZ_TYPE_OPTIONS = Object.keys(BIZ_TYPE_LABELS);

export const OPERATION_TYPE_OPTIONS = Object.keys(OPERATION_TYPE_LABELS);

export function bizTypeLabel(type?: string | null): string {
  if (!type) return "-";
  return BIZ_TYPE_LABELS[type] ?? type;
}

export function operationTypeLabel(operation?: string | null): string {
  if (!operation) return "-";
  return OPERATION_TYPE_LABELS[operation] ?? operation;
}

/**
 * 解析后端返回的 change_diff 列（JSON 数组字符串）；解析失败或为空时返回空数组
 */
export function parseChangeDiff(raw?: string | null): ChangeDiffRow[] {
  if (!raw) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
    .map((item) => ({
      field: typeof item.field === "string" && item.field ? item.field : "/",
      before: item.before ?? null,
      after: item.after ?? null
    }));
}

/**
 * 差异值转文本：空值统一显示「—」，对象/数组按 JSON 展示
 */
export function formatDiffValue(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value.length === 0 ? "「空」" : value;
  if (typeof value === "boolean" || typeof value === "number") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function summarizeChangeDiff(rows: ChangeDiffRow[]): string {
  return rows.length === 0 ? "无字段变化" : `${rows.length} 处字段变化`;
}

/**
 * 把 `datetime-local` 输入（`2026-09-26T12:30`）转成后端约定的 `yyyy-MM-dd HH:mm:ss`
 */
export function toQueryDateTime(localValue?: string | null): string | undefined {
  if (!localValue) return undefined;
  const normalized = localValue.trim().replace("T", " ");
  if (!normalized) return undefined;
  return normalized.length === 16 ? `${normalized}:00` : normalized;
}

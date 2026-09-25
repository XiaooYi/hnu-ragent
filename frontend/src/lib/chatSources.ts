/**
 * 回答来源的展示规则（纯函数，便于单测）
 *
 * 后端按文档去重并赋号（index 从 1 开始）；前端只做“怎么显示”的决策：
 * - 带外链的来源（url / feishu）点击新窗口打开；
 * - 本地文件来源暂时不跳转（文档预览由后续 UP-18b 提供）；
 * - 类型标签取文件后缀，缺失时回退来源类型。
 */

import type { SourceRef } from "@/types";

export interface SourceViewItem {
  index: number;
  title: string;
  typeLabel: string;
  excerpt: string;
  url: string | null;
  openInNewTab: boolean;
  /** 本地文件来源的站内预览地址；没有 docId 或带外链时为 null */
  previewPath: string | null;
}

const normalizeText = (value?: string | null): string => (value ?? "").trim();

export const resolveSourceTitle = (source: SourceRef): string => {
  const name = normalizeText(source.docName);
  return name || `来源 ${source.index}`;
};

export const resolveSourceTypeLabel = (source: SourceRef): string => {
  const fileType = normalizeText(source.fileType);
  if (fileType) {
    return fileType.replace(/^\./, "").toUpperCase();
  }
  const sourceType = normalizeText(source.sourceType);
  return sourceType ? sourceType.toUpperCase() : "文档";
};

export const resolveSourceItems = (sources?: SourceRef[] | null): SourceViewItem[] => {
  if (!sources || sources.length === 0) return [];
  return sources
    .filter((source) => source && Number.isFinite(source.index))
    .map((source) => {
      const url = normalizeText(source.url) || null;
      const docId = normalizeText(source.docId);
      return {
        index: source.index,
        title: resolveSourceTitle(source),
        typeLabel: resolveSourceTypeLabel(source),
        excerpt: normalizeText(source.excerpt),
        url,
        openInNewTab: Boolean(url),
        previewPath: !url && docId ? `/preview/${docId}` : null
      };
    });
};

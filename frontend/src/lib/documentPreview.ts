/**
 * 文档预览的类型判定与正文解析（纯函数，便于单测）
 *
 * 知识库管理页与「来源预览页」共用同一套判定，避免两处各写一份导致行为漂移。
 */

export type PreviewMode = "pdf" | "spreadsheet" | "image" | "csv" | "markdown" | "text" | "download";

const SPREADSHEET_EXTS = ["xlsx", "xls"];
const IMAGE_EXTS = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"];
const MARKDOWN_EXTS = ["md", "markdown"];

export const normalizeFileType = (fileType?: string | null): string =>
  (fileType ?? "").trim().toLowerCase().replace(/^\./, "");

export const isSpreadsheetType = (fileType?: string | null): boolean =>
  SPREADSHEET_EXTS.includes(normalizeFileType(fileType));

export const isImageType = (fileType?: string | null): boolean =>
  IMAGE_EXTS.includes(normalizeFileType(fileType));

export const isPdfType = (fileType?: string | null): boolean => normalizeFileType(fileType) === "pdf";

export const isCsvType = (fileType?: string | null): boolean => normalizeFileType(fileType) === "csv";

export const isMarkdownType = (fileType?: string | null): boolean =>
  MARKDOWN_EXTS.includes(normalizeFileType(fileType));

/**
 * 解析预览方式：直出原文件的类型（pdf / 表格 / 图片）不需要预取正文
 */
export const resolvePreviewMode = (fileType?: string | null): PreviewMode => {
  if (isPdfType(fileType)) return "pdf";
  if (isSpreadsheetType(fileType)) return "spreadsheet";
  if (isImageType(fileType)) return "image";
  if (isCsvType(fileType)) return "csv";
  if (isMarkdownType(fileType)) return "markdown";
  const normalized = normalizeFileType(fileType);
  if (normalized === "txt") return "text";
  return "download";
};

/** 需要拉取正文文本的预览方式 */
export const needsTextContent = (fileType?: string | null): boolean => {
  const mode = resolvePreviewMode(fileType);
  return mode === "csv" || mode === "markdown" || mode === "text";
};

/**
 * 剥离 markdown 头部的 front-matter，单独展示（解析器写入的元信息不该混进正文）
 */
export const parseFrontMatter = (content: string): { head: string | null; body: string } => {
  if (!content || !content.startsWith("---\n")) {
    return { head: null, body: content ?? "" };
  }
  const end = content.indexOf("\n---\n", 4);
  if (end <= 0) {
    return { head: null, body: content };
  }
  return { head: content.substring(4, end), body: content.substring(end + 5) };
};

/** 源文件地址（图片 / PDF / 下载都用它） */
export const documentFileUrl = (docId: string, baseUrl?: string): string =>
  `${(baseUrl ?? "").replace(/\/$/, "")}/knowledge-base/docs/${docId}/file`;

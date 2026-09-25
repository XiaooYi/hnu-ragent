import { lazy, Suspense, useEffect, useState } from "react";
import { Download } from "lucide-react";

import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { csvToMarkdown } from "@/lib/csvToMarkdown";
import {
  documentFileUrl,
  needsTextContent,
  parseFrontMatter,
  resolvePreviewMode
} from "@/lib/documentPreview";
import { fetchDocumentFile, previewDocument } from "@/services/knowledgeService";

// xlsx 预览依赖较重（exceljs + x-data-spreadsheet），懒加载避免拖累主包
const SpreadsheetPreview = lazy(() =>
  import("@/components/admin/SpreadsheetPreview").then((m) => ({ default: m.SpreadsheetPreview }))
);

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

interface DocumentPreviewProps {
  docId: string;
  fileType?: string | null;
  docName?: string | null;
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-1 items-center justify-center py-16 text-sm text-muted-foreground">
      {children}
    </div>
  );
}

function DownloadFallback({ docId, docName, fileType }: DocumentPreviewProps) {
  const handleDownload = async () => {
    const buffer = await fetchDocumentFile(docId);
    const url = URL.createObjectURL(new Blob([buffer]));
    const anchor = document.createElement("a");
    anchor.href = url;
    const name = docName || `document-${docId}`;
    const hasExt = /\.[^./\\]+$/.test(name);
    anchor.download = !hasExt && fileType ? `${name}.${fileType.toLowerCase()}` : name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-sm text-muted-foreground">
      <span>该格式暂不支持在线预览</span>
      <button
        type="button"
        onClick={handleDownload}
        className="inline-flex items-center gap-1.5 rounded-full border border-[#EAEAEA] bg-[#F7F7F8] px-3 py-1.5 text-[#666666] transition-colors hover:border-[#DCDCDC] hover:bg-[#F0F0F1] hover:text-[#1A1A1A]"
      >
        <Download className="h-3.5 w-3.5" />
        下载原文件
      </button>
    </div>
  );
}

/**
 * 文档预览：按文件类型直出原文件
 * <p>
 * pdf→iframe、xlsx/xls→表格预览、图片→img、csv→表格化 markdown、markdown/txt→正文；
 * 其余类型给下载入口。判定逻辑与知识库管理页共用 `lib/documentPreview`。
 */
export function DocumentPreview({ docId, fileType, docName }: DocumentPreviewProps) {
  const mode = resolvePreviewMode(fileType);
  const [content, setContent] = useState("");
  const [status, setStatus] = useState<"loading" | "done" | "error">(
    needsTextContent(fileType) ? "loading" : "done"
  );

  useEffect(() => {
    if (!needsTextContent(fileType)) {
      setContent("");
      setStatus("done");
      return;
    }
    let cancelled = false;
    setStatus("loading");
    (async () => {
      try {
        if (mode === "csv") {
          const buffer = await fetchDocumentFile(docId);
          const text = new TextDecoder("utf-8").decode(buffer);
          if (!cancelled) {
            setContent(csvToMarkdown(text));
            setStatus("done");
          }
          return;
        }
        const text = await previewDocument(docId);
        if (!cancelled) {
          setContent(text);
          setStatus("done");
        }
      } catch {
        if (!cancelled) setStatus("error");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [docId, fileType, mode]);

  if (mode === "pdf") {
    return (
      <iframe
        className="h-full w-full flex-1 border-0"
        src={documentFileUrl(docId, API_BASE_URL)}
        title={docName || "PDF 预览"}
      />
    );
  }

  if (mode === "spreadsheet") {
    return (
      <Suspense fallback={<Centered>加载中…</Centered>}>
        <SpreadsheetPreview docId={docId} />
      </Suspense>
    );
  }

  if (mode === "image") {
    return (
      <div className="flex flex-1 items-center justify-center overflow-auto bg-muted/30 p-4">
        <img
          className="max-h-full max-w-full object-contain"
          src={documentFileUrl(docId, API_BASE_URL)}
          alt={docName || "图片预览"}
        />
      </div>
    );
  }

  if (mode === "download") {
    return <DownloadFallback docId={docId} docName={docName} fileType={fileType} />;
  }

  if (status === "loading") {
    return <Centered>正在加载…</Centered>;
  }
  if (status === "error") {
    return <Centered>无法加载该文档，可能已被删除。</Centered>;
  }

  const { head, body } = parseFrontMatter(content);
  return (
    <div className="flex-1 overflow-y-auto sidebar-scroll">
      {head ? (
        <pre className="mx-6 mt-4 overflow-auto rounded-lg border bg-slate-50 px-4 py-3 font-mono text-xs leading-relaxed text-slate-600 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-400">
          {head}
        </pre>
      ) : null}
      <div className="px-6 py-4">
        <MarkdownRenderer content={body} />
      </div>
    </div>
  );
}

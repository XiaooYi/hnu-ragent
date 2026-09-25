import { FileText, Link2 } from "lucide-react";
import { Link } from "react-router-dom";

import { resolveSourceItems } from "@/lib/chatSources";
import type { SourceRef } from "@/types";

interface SourcesPanelProps {
  sources?: SourceRef[] | null;
}

/**
 * 回答来源面板
 *
 * 编号与后端来源列表一致（index 从 1 开始），后续行内引用角标复用同一编号。
 * 本地文件来源暂不跳转（文档预览见 UP-18b），带外链的来源在新窗口打开。
 */
export function SourcesPanel({ sources }: SourcesPanelProps) {
  const items = resolveSourceItems(sources);
  if (items.length === 0) {
    return null;
  }

  return (
    <details className="mt-3 rounded-lg border border-border/60 bg-muted/30 px-3 py-2" open={false}>
      <summary className="cursor-pointer text-xs font-medium text-muted-foreground">
        参考来源（{items.length}）
      </summary>
      <ul className="mt-2 space-y-2">
        {items.map((item) => {
          const titleNode = item.url ? (
            <a
              className="font-medium text-foreground underline-offset-2 hover:underline"
              href={item.url}
              target="_blank"
              rel="noreferrer"
            >
              {item.title}
              <Link2 className="ml-1 inline h-3 w-3" />
            </a>
          ) : item.previewPath ? (
            <Link
              className="font-medium text-foreground underline-offset-2 hover:underline"
              to={item.previewPath}
              target="_blank"
              rel="noreferrer"
            >
              {item.title}
            </Link>
          ) : (
            <span className="font-medium text-foreground">{item.title}</span>
          );

          return (
            <li
              key={`${item.index}-${item.title}`}
              id={`cite-${item.index}`}
              data-citation-source={item.index}
              className="flex gap-2 rounded px-1 text-xs transition-colors target:bg-primary/10"
            >
              <span className="mt-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded bg-primary/10 px-1 font-medium text-primary">
                {item.index}
              </span>
              <div className="min-w-0 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <FileText className="h-3 w-3 text-muted-foreground" />
                  {titleNode}
                  <span className="rounded border border-border px-1 text-[10px] uppercase text-muted-foreground">
                    {item.typeLabel}
                  </span>
                </div>
                {item.excerpt ? (
                  <p className="line-clamp-2 text-muted-foreground">{item.excerpt}</p>
                ) : null}
              </div>
            </li>
          );
        })}
      </ul>
    </details>
  );
}

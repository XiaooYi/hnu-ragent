/**
 * 行内引用角标的解析与规整（纯函数，便于单测）
 *
 * 模型被要求输出 `[N](#cite-N)`，但实测会出现两种偏差：
 * 1. 只写裸标记 `[N]` 或 `【N】`，忘写链接；
 * 2. 把角标当脚注单独成段。
 *
 * 这里只负责第 1 种：把**属于本次来源编号**的裸标记补成标准链接，
 * 其余内容（普通链接、代码块、无关方括号）原样保留。
 * 角标位置的规整交给渲染层（点击时展开来源面板）。
 */

import type { SourceRef } from "@/types";

const BARE_MARKER = /\[([1-9]\d*)\]|【([1-9]\d*)】/g;
const CITATION_HREF = /^#cite-([1-9]\d*)$/;

export const buildCitationHref = (index: number): string => `#cite-${index}`;

/** 从链接地址解析引用编号；非引用链接返回 null */
export const parseCitationIndex = (href?: string | null): number | null => {
  if (!href) return null;
  const matched = CITATION_HREF.exec(href.trim());
  return matched ? Number(matched[1]) : null;
};

export const collectSourceIndexes = (sources?: SourceRef[] | null): number[] => {
  if (!sources) return [];
  return sources
    .filter((source) => source && Number.isFinite(source.index))
    .map((source) => source.index);
};

/**
 * 把属于本次来源的裸标记补成 `[N](#cite-N)`
 *
 * 代码围栏与行内代码里的内容不参与替换，避免把示例代码里的 `[1]` 变成角标
 */
export const normalizeCitationMarkers = (
  content: string,
  validIndexes: number[]
): string => {
  if (!content || validIndexes.length === 0) return content;
  const allowed = new Set(validIndexes);

  const rewrite = (segment: string): string =>
    segment.replace(BARE_MARKER, (matched, square: string | undefined, corner: string | undefined) => {
      const rawIndex = square ?? corner;
      const index = Number(rawIndex);
      return allowed.has(index) ? `[${index}](${buildCitationHref(index)})` : matched;
    });

  // 按「围栏代码块 / 行内代码 / 普通文本」切分，只有普通文本参与替换
  const parts = content.split(/(```[\s\S]*?```|`[^`\n]*`)/g);
  return parts
    .map((part, position) => (position % 2 === 1 ? part : rewrite(part)))
    .join("");
};

/**
 * 点击角标：展开来源面板并滚动到对应条目
 *
 * 面板用 `<details>` 折叠，未展开时浏览器无法滚动到内部元素，因此先把祖先 details 打开
 */
export const focusCitationSource = (index: number, root?: ParentNode | null): boolean => {
  const scope: ParentNode | null =
    root ?? (typeof document === "undefined" ? null : document);
  if (!scope) return false;
  const target = scope.querySelector(`[data-citation-source="${index}"]`);
  if (!target) return false;

  let node: HTMLElement | null = target as HTMLElement;
  while (node) {
    if (node instanceof HTMLDetailsElement) {
      node.open = true;
    }
    node = node.parentElement;
  }
  (target as HTMLElement).scrollIntoView?.({ behavior: "smooth", block: "nearest" });
  return true;
};

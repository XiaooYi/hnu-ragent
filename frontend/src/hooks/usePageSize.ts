import { useCallback, useState } from "react";

export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

export type PageSize = (typeof PAGE_SIZE_OPTIONS)[number];

const STORAGE_PREFIX = "ragent:page-size:";

const normalizePageSize = (value: number): PageSize =>
  PAGE_SIZE_OPTIONS.includes(value as PageSize) ? (value as PageSize) : DEFAULT_PAGE_SIZE;

export function usePageSize(storageKey: string) {
  const [pageSize, setPageSize] = useState<PageSize>(() => {
    if (typeof window === "undefined") return DEFAULT_PAGE_SIZE;
    try {
      const storedValue = Number(window.localStorage.getItem(`${STORAGE_PREFIX}${storageKey}`));
      return normalizePageSize(storedValue);
    } catch {
      return DEFAULT_PAGE_SIZE;
    }
  });

  const updatePageSize = useCallback((value: number) => {
    const nextPageSize = normalizePageSize(value);
    setPageSize(nextPageSize);
    try {
      window.localStorage.setItem(`${STORAGE_PREFIX}${storageKey}`, String(nextPageSize));
    } catch {
      // Pagination remains usable when browser storage is unavailable.
    }
  }, [storageKey]);

  return [pageSize, updatePageSize] as const;
}

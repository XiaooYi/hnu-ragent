/**
 * 意图 ↔ 知识库 Collection 的取值规则（纯函数，便于单测）
 *
 * 后端同时存在两个字段：
 * - `collectionNames`：新字段，列表，一个意图可覆盖多个知识库
 * - `collectionName`：旧字段，单值，仅用于兼容旧数据与旧缓存
 *
 * 前端读写统一走本文件：读时新字段优先、旧字段兜底；写时同时回填旧字段，
 * 保证老版本后端/老缓存也能识别。
 */

export interface IntentCollectionSource {
  collectionName?: string | null;
  collectionNames?: string[] | null;
}

export const normalizeCollectionNames = (
  values?: Array<string | null | undefined> | null
): string[] => {
  if (!values) return [];
  const normalized: string[] = [];
  values.forEach((value) => {
    const trimmed = value?.trim();
    if (trimmed && !normalized.includes(trimmed)) {
      normalized.push(trimmed);
    }
  });
  return normalized;
};

/** 读：新字段优先，为空时回退旧单值字段 */
export const resolveIntentCollectionNames = (
  node?: IntentCollectionSource | null
): string[] => {
  const fromList = normalizeCollectionNames(node?.collectionNames ?? null);
  if (fromList.length > 0) return fromList;
  const legacy = node?.collectionName?.trim();
  return legacy ? [legacy] : [];
};

export const addCollectionName = (current: string[], name: string): string[] =>
  normalizeCollectionNames([...current, name]);

export const removeCollectionName = (current: string[], name: string): string[] =>
  normalizeCollectionNames(current).filter((value) => value !== name.trim());

export const toggleCollectionName = (current: string[], name: string): string[] =>
  normalizeCollectionNames(current).includes(name.trim())
    ? removeCollectionName(current, name)
    : addCollectionName(current, name);

/**
 * 写：只有知识库意图（kind=0）才关联 Collection；同时回填旧单值字段供兼容读取
 */
export const buildIntentCollectionPayload = (
  collectionNames: string[] | undefined,
  kind: number
): { collectionName: string; collectionNames: string[] } => {
  const normalized = kind === 0 ? normalizeCollectionNames(collectionNames ?? null) : [];
  return {
    collectionName: normalized.length > 0 ? normalized[0] : "",
    collectionNames: normalized
  };
};

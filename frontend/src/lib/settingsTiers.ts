/**
 * 系统设置页的档位展示逻辑。
 *
 * 档位是「质量 / 成本 / 时延预算」（fast / standard / deep），每个档位 = 一组有序候选 +
 * 一个调用预算。这里只做展示层整理，判定与路由完全由后端 ChatTierConfigValidator /
 * ModelSelector 负责。
 */

export interface TierConfig {
  /** 档位内候选模型 id，靠前优先 */
  candidates?: string[] | null;
  /** 档位调用预算（毫秒）：流式为首包预算，同步为整段上限 */
  timeoutMs?: number | null;
}

/** 已知档位的展示顺序；未在其中出现的档位排在其后，保持接口返回顺序 */
export const TIER_DISPLAY_ORDER = ["fast", "standard", "deep"] as const;

export interface TierRow {
  name: string;
  candidates: string;
  timeout: string;
}

export function sortTierNames(tiers?: Record<string, TierConfig> | null): string[] {
  if (!tiers) {
    return [];
  }
  const names = Object.keys(tiers);
  const known = TIER_DISPLAY_ORDER.filter((tier) => names.includes(tier));
  const unknown = names.filter((name) => !(TIER_DISPLAY_ORDER as readonly string[]).includes(name));
  return [...known, ...unknown];
}

export function formatTierCandidates(config?: TierConfig | null): string {
  const candidates = config?.candidates ?? [];
  return candidates.length > 0 ? candidates.join(" → ") : "-";
}

export function formatTierTimeout(config?: TierConfig | null): string {
  return config?.timeoutMs == null ? "-" : String(config.timeoutMs);
}

export function tierRows(tiers?: Record<string, TierConfig> | null): TierRow[] {
  return sortTierNames(tiers).map((name) => {
    const config = tiers?.[name];
    return {
      name,
      candidates: formatTierCandidates(config),
      timeout: formatTierTimeout(config)
    };
  });
}

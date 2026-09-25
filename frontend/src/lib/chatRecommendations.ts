/**
 * 推荐追问的展示规则（纯函数，便于单测）
 *
 * 状态挂在消息对象上（`recommended` / `recommendedState` / `recommendedOpen`），天然按消息隔离；
 * 这里只负责「该不该显示入口」与「接口返回 / 历史数据如何映射成消息状态」两类决策。
 */

import type { Message, RecommendedQuestionsPayload } from "@/types";

export type RecommendedState = "idle" | "loading" | "ready" | "error";

/**
 * 是否显示推荐入口：仅「回答已完成」且消息状态为 NORMAL 的助手消息
 * （中断 / 被拒的回答内容不完整，据它生成的追问会偏离）
 */
export const shouldOfferRecommendations = (message: Message): boolean => {
  if (message.role !== "assistant") return false;
  if (message.status && message.status !== "done") return false;
  if (message.messageStatus && message.messageStatus !== "NORMAL") return false;
  return true;
};

/**
 * 接口返回 → 消息状态
 * SUCCESS / EMPTY 都算「已就绪」（EMPTY 是负缓存，避免重复请求）；FAILED 可重试
 */
export const resolveRecommendationState = (
  payload?: RecommendedQuestionsPayload | null
): { state: RecommendedState; questions: string[] } => {
  if (!payload || payload.status === "FAILED") {
    return { state: "error", questions: [] };
  }
  const questions = Array.isArray(payload.questions)
    ? payload.questions.map((item) => item.trim()).filter(Boolean)
    : [];
  return { state: "ready", questions };
};

/**
 * 历史消息 → 初始推荐状态：后端返回 null 表示未生成，空数组表示已生成但无合适追问
 */
export const initialRecommendationState = (
  recommendedQuestions?: string[] | null
): { state: RecommendedState; questions: string[] } => {
  if (recommendedQuestions == null) {
    return { state: "idle", questions: [] };
  }
  return {
    state: "ready",
    questions: recommendedQuestions.map((item) => item.trim()).filter(Boolean)
  };
};

/** 是否需要在展开时请求接口（已就绪或正在加载都不重复请求） */
export const needsRecommendationFetch = (message: Message): boolean =>
  message.recommendedState === undefined || message.recommendedState === "idle";

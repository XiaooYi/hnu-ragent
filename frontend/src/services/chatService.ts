import { api } from "@/services/api";
import type { RecommendedQuestionsPayload } from "@/types";

export async function stopTask(taskId: string) {
  return api.post<void>(`/rag/v3/stop?taskId=${encodeURIComponent(taskId)}`);
}

export async function submitFeedback(messageId: string, vote: number) {
  return api.post<void>(`/conversations/messages/${messageId}/feedback`, {
    vote
  });
}

/** 读取已缓存的推荐追问（未生成时后端返回业务错误） */
export async function getRecommendedQuestions(messageId: string) {
  return api.get<RecommendedQuestionsPayload, RecommendedQuestionsPayload>(
    `/conversations/messages/${messageId}/recommended-questions`
  );
}

/** 幂等生成推荐追问（已有缓存直接返回，失败可重试） */
export async function generateRecommendedQuestions(messageId: string) {
  return api.post<RecommendedQuestionsPayload>(
    `/conversations/messages/${messageId}/recommended-questions`
  );
}

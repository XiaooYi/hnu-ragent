import { api } from "@/services/api";

export interface ConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
}

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  thinkingContent?: string | null;
  thinkingDuration?: number | null;
  vote: number | null;
  /** 回答来源（文档级来源列表） */
  sources?: Array<{ index: number; docName?: string | null }> | null;
  /** 已生成的推荐追问；null=未生成，[]=已生成但无合适追问 */
  recommendedQuestions?: string[] | null;
  /** 消息结束状态：NORMAL / INTERRUPTED / REJECTED */
  messageStatus?: string | null;
  createTime?: string;
}

export async function listSessions() {
  return api.get<ConversationVO[]>("/conversations");
}

export async function deleteSession(conversationId: string) {
  return api.delete<void>(`/conversations/${conversationId}`);
}

export async function renameSession(conversationId: string, title: string) {
  return api.put<void>(`/conversations/${conversationId}`, { title });
}

export async function listMessages(conversationId: string) {
  return api.get<ConversationMessageVO[]>(`/conversations/${conversationId}/messages`);
}

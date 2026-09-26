import { api } from "@/services/api";
import { createStreamResponse, type StreamHandlers } from "@/hooks/useStreamResponse";
import { storage } from "@/utils/storage";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

export interface AgentConversation {
  conversationId: string;
  title: string;
  lastTime?: string | null;
  createTime?: string | null;
}

export interface AgentMessage {
  id: string;
  conversationId: string;
  role: string;
  content?: string | null;
  blocks?: string | null;
  replyToMessageId?: string | null;
  messageStatus?: string | null;
  durationMs?: number | null;
  createTime?: string | null;
}

/**
 * Agent 流式对话：事件协议见 lib/agentStream.ts
 */
export function streamAgentChat(
  question: string,
  conversationId: string | undefined,
  handlers: StreamHandlers
) {
  const params = new URLSearchParams();
  params.set("question", question);
  if (conversationId) {
    params.set("conversationId", conversationId);
  }
  const token = storage.getToken();
  return createStreamResponse(
    {
      url: `${API_BASE_URL}/agent/chat?${params.toString()}`,
      headers: token ? { Authorization: token } : undefined,
      retryCount: 0
    },
    handlers
  );
}

export async function getAgentConversations(): Promise<AgentConversation[]> {
  return api.get<AgentConversation[], AgentConversation[]>("/agent/conversations");
}

export async function getAgentMessages(conversationId: string): Promise<AgentMessage[]> {
  return api.get<AgentMessage[], AgentMessage[]>(
    `/agent/conversations/${encodeURIComponent(conversationId)}/messages`
  );
}

export async function deleteAgentConversation(conversationId: string): Promise<void> {
  await api.delete(`/agent/conversations/${encodeURIComponent(conversationId)}`);
}

/**
 * 用户确认后执行写操作
 */
export async function confirmAgentTool(
  toolId: string,
  args: Record<string, unknown>
): Promise<string> {
  return api.post<string, string>("/agent/confirm", { toolId, arguments: args });
}

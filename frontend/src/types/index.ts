export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  /** 回答来源（文档级来源列表，仅 assistant 消息可能有） */
  sources?: SourceRef[];
}

/**
 * 回答来源（文档级）
 * index 从 1 开始，与后端 SSE / 落库 / 行内引用角标共用同一编号
 */
export interface SourceRef {
  index: number;
  docId?: string | null;
  docName?: string | null;
  sourceType?: string | null;
  fileType?: string | null;
  url?: string | null;
  excerpt?: string | null;
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
  sources?: SourceRef[] | null;
}

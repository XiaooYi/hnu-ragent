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
  /** 已生成的推荐追问（undefined/[]=已生成但无合适追问；未生成时由 recommendedState 表示） */
  recommended?: string[];
  recommendedState?: "idle" | "loading" | "ready" | "error";
  recommendedOpen?: boolean;
  /** 消息结束状态：NORMAL / INTERRUPTED / REJECTED */
  messageStatus?: string | null;
  /** 生成失败的原因，用于在气泡内直接提示用户 */
  errorMessage?: string;
}

/**
 * 推荐追问接口返回
 */
export interface RecommendedQuestionsPayload {
  status: "SUCCESS" | "EMPTY" | "FAILED";
  questions: string[];
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
  messageStatus?: string | null;
}

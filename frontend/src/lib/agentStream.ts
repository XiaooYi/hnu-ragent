/**
 * Agent 流式对话的前端状态归约。
 *
 * 后端事件协议（见 agent/enums/AgentSSEEventType）：
 *   meta → tool… → (hint) → (confirm) → message → finish
 * 这里把它归约成页面直接可渲染的状态，纯函数、可单测；页面只负责渲染与发请求。
 */

export type AgentToolStatus = "running" | "done";

export interface AgentToolRow {
  step: number;
  toolId: string;
  arguments?: Record<string, unknown>;
  observation?: string | null;
  latencyMs?: number | null;
  status: AgentToolStatus;
}

export interface AgentConfirmCall {
  toolId: string;
  arguments: Record<string, unknown>;
  fieldLabels: Record<string, string>;
  stepIndex: number;
}

export interface AgentStreamState {
  running: boolean;
  conversationId: string | null;
  tools: AgentToolRow[];
  hints: string[];
  confirm: AgentConfirmCall | null;
  answer: string;
  stopReason: string | null;
}

export function initialAgentStreamState(conversationId: string | null = null): AgentStreamState {
  return {
    running: false,
    conversationId,
    tools: [],
    hints: [],
    confirm: null,
    answer: "",
    stopReason: null
  };
}

/**
 * 归约一条 SSE 事件；未知事件原样返回状态（前向兼容：后端加事件不会让页面崩）
 */
export function reduceAgentEvent(
  state: AgentStreamState,
  event: string,
  payload: unknown
): AgentStreamState {
  const data = (payload ?? {}) as Record<string, unknown>;
  switch (event) {
    case "meta":
      return {
        ...state,
        running: true,
        conversationId: (data.conversationId as string) ?? state.conversationId,
        stopReason: null
      };
    case "tool":
      return {
        ...state,
        tools: [
          ...state.tools,
          {
            step: Number(data.step ?? state.tools.length + 1),
            toolId: String(data.toolId ?? "unknown"),
            arguments: (data.arguments as Record<string, unknown>) ?? {},
            latencyMs: (data.latencyMs as number) ?? null,
            status: "done"
          }
        ]
      };
    case "hint":
      return { ...state, hints: [...state.hints, String(data.message ?? "")] };
    case "confirm":
      return {
        ...state,
        confirm: {
          toolId: String(data.toolId ?? ""),
          arguments: (data.arguments as Record<string, unknown>) ?? {},
          fieldLabels: (data.fieldLabels as Record<string, string>) ?? {},
          stepIndex: Number(data.stepIndex ?? 0)
        }
      };
    case "message":
      return { ...state, answer: String(data.content ?? "") };
    case "finish":
      return {
        ...state,
        running: false,
        stopReason: String(data.reason ?? ""),
        tools: state.tools.map((tool) => ({ ...tool, status: "done" as AgentToolStatus }))
      };
    default:
      return state;
  }
}

/**
 * 解析历史消息里的工具块（后端存的是 JSON 数组字符串），供历史回放渲染
 */
export function parseToolBlocks(raw?: string | null): AgentToolRow[] {
  if (!raw) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
    .map((item, index) => ({
      step: Number(item.step ?? index + 1),
      toolId: String(item.toolId ?? "unknown"),
      arguments: (item.arguments as Record<string, unknown>) ?? {},
      observation: (item.observation as string) ?? null,
      latencyMs: (item.latencyMs as number) ?? null,
      status: "done" as AgentToolStatus
    }));
}

export function summarizeToolArguments(args?: Record<string, unknown>): string {
  if (!args || Object.keys(args).length === 0) return "无参数";
  return Object.entries(args)
    .map(([key, value]) => `${key}=${value === null || value === undefined ? "" : String(value)}`)
    .join("，");
}

export function stopReasonLabel(reason?: string | null): string {
  switch (reason) {
    case "FINAL_ANSWER":
      return "回答完成";
    case "MAX_STEPS":
      return "达到工具调用上限";
    case "CONFIRM_REQUIRED":
      return "等待确认";
    case "FALLBACK_TEXT":
      return "直接回答";
    case "INTERRUPTED":
      return "已停止";
    case "FAILED":
      return "回答失败";
    default:
      return "";
  }
}

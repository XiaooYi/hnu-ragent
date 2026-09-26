import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, Bot, Loader2, Plus, Send, ShieldAlert, Trash2, Wrench } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  initialAgentStreamState,
  parseToolBlocks,
  reduceAgentEvent,
  stopReasonLabel,
  summarizeToolArguments,
  type AgentStreamState,
  type AgentToolRow
} from "@/lib/agentStream";
import {
  confirmAgentTool,
  deleteAgentConversation,
  getAgentConversations,
  getAgentMessages,
  streamAgentChat,
  type AgentConversation,
  type AgentMessage
} from "@/services/agentService";
import { getErrorMessage } from "@/utils/error";

interface ChatTurn {
  question: string;
  answer: string;
  tools: AgentToolRow[];
  hints: string[];
  stopReason?: string | null;
}

export function AgentChatPage() {
  const [question, setQuestion] = useState("");
  const [stream, setStream] = useState<AgentStreamState>(initialAgentStreamState());
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [conversations, setConversations] = useState<AgentConversation[]>([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const streamRef = useRef<ReturnType<typeof streamAgentChat> | null>(null);

  const loadConversations = () => {
    getAgentConversations()
      .then((items) => setConversations(items || []))
      .catch((error) => console.error(error));
  };

  useEffect(() => {
    loadConversations();
  }, []);

  const handleSend = async () => {
    const trimmed = question.trim();
    if (!trimmed || stream.running) return;
    setQuestion("");
    const turn: ChatTurn = { question: trimmed, answer: "", tools: [], hints: [] };
    setTurns((prev) => [...prev, turn]);
    setStream({ ...initialAgentStreamState(stream.conversationId), running: true });

    let state = initialAgentStreamState(stream.conversationId);
    state.running = true;

    const runner = streamAgentChat(trimmed, stream.conversationId ?? undefined, {
      onEvent: (event, payload) => {
        state = reduceAgentEvent(state, event, payload);
        setStream({ ...state });
        setTurns((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (!last || last.question !== trimmed) return prev;
          next[next.length - 1] = {
            ...last,
            answer: state.answer,
            tools: state.tools,
            hints: state.hints,
            stopReason: state.stopReason
          };
          return next;
        });
      },
      onError: (error) => {
        toast.error(getErrorMessage(error, "Agent 对话失败（请确认 ai.agent.enabled=true）"));
      }
    });
    streamRef.current = runner;
    try {
      await runner.start();
    } catch (error) {
      toast.error(getErrorMessage(error, "Agent 对话失败"));
    } finally {
      setStream((prev) => ({ ...prev, running: false }));
      loadConversations();
    }
  };

  const handleOpenConversation = async (conversationId: string) => {
    setLoadingHistory(true);
    try {
      const messages = await getAgentMessages(conversationId);
      setTurns(buildTurns(messages));
      setStream(initialAgentStreamState(conversationId));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载会话失败"));
    } finally {
      setLoadingHistory(false);
    }
  };

  const handleDelete = async (conversationId: string) => {
    try {
      await deleteAgentConversation(conversationId);
      if (stream.conversationId === conversationId) {
        setTurns([]);
        setStream(initialAgentStreamState());
      }
      loadConversations();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除会话失败"));
    }
  };

  const handleConfirm = async (approve: boolean) => {
    if (!stream.confirm) return;
    const call = stream.confirm;
    setStream((prev) => ({ ...prev, confirm: null }));
    if (!approve) {
      toast.info("已取消该写操作，未执行任何修改");
      return;
    }
    try {
      const observation = await confirmAgentTool(call.toolId, call.arguments);
      toast.success("已执行");
      setTurns((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last) {
          next[next.length - 1] = {
            ...last,
            answer: `${last.answer}\n\n执行结果：${observation}`
          };
        }
        return next;
      });
    } catch (error) {
      toast.error(getErrorMessage(error, "执行失败"));
    }
  };

  const handleNewConversation = () => {
    setTurns([]);
    setStream(initialAgentStreamState());
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">Agent 对话</h1>
          <p className="admin-page-subtitle">
            会先判断是否需要查资料，再决定调用哪些工具；写操作需要你确认后才会执行
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" asChild>
            <Link to="/chat">
              <ArrowLeft className="mr-2 h-4 w-4" />
              返回 RAG 问答
            </Link>
          </Button>
          <Button className="admin-primary-gradient" onClick={handleNewConversation}>
            <Plus className="mr-2 h-4 w-4" />
            新会话
          </Button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)]">
        <Card>
          <CardContent className="space-y-2 pt-6">
            <div className="text-xs font-medium text-muted-foreground">历史会话</div>
            {conversations.length === 0 ? (
              <div className="text-sm text-muted-foreground">暂无历史会话</div>
            ) : (
              conversations.map((conversation) => (
                <div
                  key={conversation.conversationId}
                  className={`flex items-center justify-between gap-2 rounded-lg border px-3 py-2 text-sm ${
                    stream.conversationId === conversation.conversationId ? "border-slate-900" : ""
                  }`}
                >
                  <button
                    type="button"
                    className="min-w-0 flex-1 truncate text-left"
                    onClick={() => handleOpenConversation(conversation.conversationId)}
                    title={conversation.title}
                  >
                    {conversation.title}
                  </button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-muted-foreground"
                    onClick={() => handleDelete(conversation.conversationId)}
                    aria-label="删除会话"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-4 pt-6">
            {loadingHistory ? (
              <div className="py-8 text-center text-muted-foreground">加载会话中...</div>
            ) : turns.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">
                问一个需要查资料的问题，例如「转专业需要哪些材料」
              </div>
            ) : (
              <div className="space-y-4">
                {turns.map((turn, index) => (
                  <div key={`${index}-${turn.question}`} className="space-y-2">
                    <div className="flex justify-end">
                      <div className="max-w-[80%] rounded-lg bg-slate-900 px-3 py-2 text-sm text-white">
                        {turn.question}
                      </div>
                    </div>
                    {turn.tools.length > 0 ? (
                      <div className="space-y-1">
                        {turn.tools.map((tool) => (
                          <div
                            key={`${tool.step}-${tool.toolId}`}
                            className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600"
                          >
                            <Badge variant="secondary">
                              <Wrench className="mr-1 h-3 w-3" />
                              工具 {tool.toolId}
                            </Badge>
                            <span>{summarizeToolArguments(tool.arguments)}</span>
                            {tool.latencyMs ? <span>· {tool.latencyMs}ms</span> : null}
                          </div>
                        ))}
                      </div>
                    ) : null}
                    {turn.hints.map((hint) => (
                      <div key={hint} className="text-xs text-amber-600">
                        {hint}
                      </div>
                    ))}
                    {turn.answer ? (
                      <div className="flex gap-2">
                        <Bot className="mt-1 h-4 w-4 shrink-0 text-slate-500" />
                        <div className="max-w-[80%] whitespace-pre-wrap rounded-lg border bg-white px-3 py-2 text-sm">
                          {turn.answer}
                          {turn.stopReason ? (
                            <div className="mt-1 text-xs text-muted-foreground">
                              {stopReasonLabel(turn.stopReason)}
                            </div>
                          ) : null}
                        </div>
                      </div>
                    ) : stream.running && index === turns.length - 1 ? (
                      <div className="flex items-center gap-2 text-sm text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin" />
                        正在思考与调用工具...
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            )}

            {stream.confirm ? (
              <div className="space-y-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3">
                <div className="flex items-center gap-2 text-sm font-medium text-amber-800">
                  <ShieldAlert className="h-4 w-4" />
                  这个操作会修改数据，确认后才会执行
                </div>
                <div className="text-sm text-amber-900">
                  工具：{stream.confirm.toolId}
                  <div className="mt-1 space-y-1 text-xs">
                    {Object.entries(stream.confirm.arguments).map(([key, value]) => (
                      <div key={key}>
                        {stream.confirm?.fieldLabels[key] || key}：{String(value)}
                      </div>
                    ))}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => handleConfirm(true)}>
                    确认执行
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => handleConfirm(false)}>
                    取消
                  </Button>
                </div>
              </div>
            ) : null}

            <div className="flex gap-2">
              <Input
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && handleSend()}
                placeholder="问点什么，例如：转专业需要哪些材料"
                disabled={stream.running}
              />
              <Button onClick={handleSend} disabled={stream.running || !question.trim()}>
                {stream.running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

/**
 * 历史消息 → 对话轮次：把 user/assistant 配对，并把 assistant 的工具块解析出来
 */
export function buildTurns(messages: AgentMessage[]): ChatTurn[] {
  const turns: ChatTurn[] = [];
  for (const message of messages) {
    if (message.role === "user") {
      turns.push({ question: message.content || "", answer: "", tools: [], hints: [] });
      continue;
    }
    const last = turns[turns.length - 1];
    if (!last) {
      turns.push({
        question: "",
        answer: message.content || "",
        tools: parseToolBlocks(message.blocks),
        hints: [],
        stopReason: message.messageStatus
      });
      continue;
    }
    last.answer = message.content || "";
    last.tools = parseToolBlocks(message.blocks);
    last.stopReason = message.messageStatus;
  }
  return turns;
}

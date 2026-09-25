import { RotateCw, Sparkles } from "lucide-react";

import { useChatStore } from "@/stores/chatStore";
import type { Message } from "@/types";

interface RecommendedQuestionsProps {
  message: Message;
}

/**
 * 追问推荐面板：内联渲染于助手消息操作行下方
 * <p>
 * 展开态与加载态都挂在消息对象上（`recommendedOpen` / `recommendedState`），天然按消息隔离
 */
export function RecommendedQuestions({ message }: RecommendedQuestionsProps) {
  const sendMessage = useChatStore((state) => state.sendMessage);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const loadRecommended = useChatStore((state) => state.loadRecommended);
  const toggleRecommended = useChatStore((state) => state.toggleRecommended);

  if (!message.recommendedOpen) {
    return null;
  }

  const state = message.recommendedState ?? "idle";
  const questions = message.recommended ?? [];

  return (
    <div className="animate-fade-up mt-2 overflow-hidden rounded-2xl border border-[#EFEFEF] bg-[#FAFAFA] p-1.5 dark:border-border dark:bg-muted/30">
      <div className="flex items-center gap-1.5 px-2.5 pb-1 pt-1.5">
        <Sparkles className="h-3.5 w-3.5 text-[#3B82F6]" />
        <span className="text-xs font-medium text-[#666666] dark:text-muted-foreground">猜你想问</span>
      </div>

      {state === "loading" || state === "idle" ? (
        <ul className="space-y-0.5" aria-label="推荐问题加载中">
          {[68, 52, 60].map((width, idx) => (
            <li key={idx} className="px-3 py-2.5">
              <div
                className="animate-pulse h-3.5 rounded-full bg-[#ECECEC] dark:bg-muted"
                style={{ width: `${width}%` }}
              />
            </li>
          ))}
        </ul>
      ) : null}

      {state === "error" ? (
        <div className="flex items-center justify-between gap-3 px-3 py-2.5">
          <span className="text-sm text-[#999999]">推荐问题加载失败</span>
          <button
            type="button"
            onClick={() => void loadRecommended(message.id)}
            className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-[#2563EB] transition-colors hover:bg-[#EAF1FF]"
          >
            <RotateCw className="h-3 w-3" />
            重试
          </button>
        </div>
      ) : null}

      {state === "ready" && questions.length === 0 ? (
        <div className="px-3 py-2.5 text-sm text-[#999999]">暂无推荐问题</div>
      ) : null}

      {state === "ready" && questions.length > 0 ? (
        <ul className="space-y-0.5">
          {questions.map((question, idx) => (
            <li key={`${idx}-${question}`}>
              <button
                type="button"
                disabled={isStreaming}
                onClick={() => {
                  // 点击即追问：复用现有发送链路，发送后收起面板
                  toggleRecommended(message.id);
                  void sendMessage(question);
                }}
                className="flex w-full items-start gap-2 rounded-xl px-3 py-2 text-left text-sm text-[#333333] transition-colors hover:bg-[#F0F5FF] disabled:cursor-not-allowed disabled:opacity-60 dark:text-foreground"
              >
                <span className="mt-0.5 text-[#3B82F6]">·</span>
                <span className="flex-1">{question}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

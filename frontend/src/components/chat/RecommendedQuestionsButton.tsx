import { ChevronDown, Loader2, Sparkles } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import type { Message } from "@/types";

interface RecommendedQuestionsButtonProps {
  message: Message;
}

/**
 * 推荐追问入口按钮：点击才请求（列表接口会按需生成），避免每条消息都自动调模型
 */
export function RecommendedQuestionsButton({ message }: RecommendedQuestionsButtonProps) {
  const toggleRecommended = useChatStore((state) => state.toggleRecommended);

  const open = Boolean(message.recommendedOpen);
  // 只有用户可见的加载（已展开）才转圈
  const spinning = message.recommendedState === "loading" && open;

  return (
    <button
      type="button"
      onClick={() => toggleRecommended(message.id)}
      disabled={spinning}
      aria-expanded={open}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full py-1 pl-2.5 pr-2 text-xs transition-colors",
        open
          ? "bg-[#EAF1FF] text-[#2563EB]"
          : "text-[#666666] hover:bg-[#F0F0F1] hover:text-[#1A1A1A] dark:text-muted-foreground",
        spinning && "cursor-wait opacity-80"
      )}
    >
      {spinning ? (
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      ) : (
        <Sparkles className={cn("h-3.5 w-3.5", open && "text-[#3B82F6]")} />
      )}
      推荐问题
      <ChevronDown className={cn("h-3 w-3 transition-transform", open && "rotate-180")} aria-hidden="true" />
    </button>
  );
}

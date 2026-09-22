import * as React from "react";
import { ArrowDownToLine, ArrowUpToLine } from "lucide-react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";

import { MessageItem } from "@/components/chat/MessageItem";
import { QuestionRail, type QuestionRailItem } from "@/components/chat/QuestionRail";
import { WelcomeScreen } from "@/components/chat/WelcomeScreen";
import { Button } from "@/components/ui/button";
import { useChatAutoScroll } from "@/hooks/useChatAutoScroll";
import { cn } from "@/lib/utils";
import type { Message } from "@/types";

interface MessageListProps {
  messages: Message[];
  isLoading: boolean;
  isStreaming: boolean;
  sessionKey?: string | null;
}

export function MessageList({ messages, isLoading, isStreaming, sessionKey }: MessageListProps) {
  const virtuosoRef = React.useRef<VirtuosoHandle | null>(null);
  const latestUserId = React.useMemo(
    () => [...messages].reverse().find((message) => message.role === "user")?.id,
    [messages]
  );
  const lastMessage = messages[messages.length - 1];
  const scroll = useChatAutoScroll({
    sessionKey,
    latestUserId,
    content: lastMessage?.content,
    thinking: lastMessage?.thinking,
    isLoading
  });
  const { pause, scrollToBottom, onListHeightChanged, scrollerRef } = scroll;
  const initialTopMostItemIndex = React.useMemo(
    () => ({ index: "LAST" as const, align: "end" as const }),
    []
  );
  const [visibleEnd, setVisibleEnd] = React.useState(0);

  const userQuestions = React.useMemo<QuestionRailItem[]>(() => {
    const items: QuestionRailItem[] = [];
    messages.forEach((msg, flatIndex) => {
      if (msg.role !== "user") return;
      const text = msg.content.replace(/\s+/g, " ").trim();
      if (!text) return;
      items.push({ id: msg.id, flatIndex, text });
    });
    return items;
  }, [messages]);

  const activeQuestionId = React.useMemo(() => {
    if (userQuestions.length === 0) return null;
    let last: string | null = userQuestions[0].id;
    for (const q of userQuestions) {
      if (q.flatIndex <= visibleEnd) {
        last = q.id;
      } else {
        break;
      }
    }
    return last;
  }, [userQuestions, visibleEnd]);

  const handleSelectQuestion = React.useCallback(
    (flatIndex: number) => {
      pause();
      virtuosoRef.current?.scrollToIndex({
        index: flatIndex,
        align: "start",
        behavior: "auto"
      });
    },
    [pause]
  );

  const handleScrollToTop = React.useCallback(() => {
    pause();
    virtuosoRef.current?.scrollToIndex({ index: 0, align: "start", behavior: "auto" });
  }, [pause]);

  const handleRangeChanged = React.useCallback(
    (range: { startIndex: number; endIndex: number }) => {
      setVisibleEnd(range.endIndex);
    },
    []
  );

  // Intercept triple-click at mousedown phase to prevent browser from
  // extending paragraph selection across sibling message boundaries.
  // preventDefault() stops the default selection, then we manually select
  // only the clicked block-level element's contents.
  const handleTripleClickDown = React.useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (e.detail < 3) return;
    e.preventDefault();
    const target = e.target as HTMLElement;
    const block = target.closest("p, li, h1, h2, h3, h4, h5, h6, pre, blockquote, td, th");
    const container = block && e.currentTarget.contains(block) ? block : e.currentTarget;
    const sel = window.getSelection();
    if (sel) {
      const range = document.createRange();
      range.selectNodeContents(container);
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }, []);

  const List = React.useMemo(() => {
    const Comp = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
      ({ className, ...props }, ref) => (
        <div
          ref={ref}
          className={cn("mx-auto max-w-[840px] space-y-10 px-6 pt-10 pb-2 md:px-8", className)}
          {...props}
        />
      )
    );
    Comp.displayName = "MessageList";
    return Comp;
  }, []);

  const Footer = React.useMemo(() => {
    const Comp = () => <div aria-hidden="true" className="h-28" />;
    Comp.displayName = "MessageListFooter";
    return Comp;
  }, []);

  if (messages.length === 0) {
    if (isLoading) {
      return <div className="h-full" />;
    }
    return <WelcomeScreen />;
  }

  return (
    <div className="relative h-full">
      <Virtuoso
        key={sessionKey ?? "empty"}
        ref={virtuosoRef}
        data={messages}
        initialTopMostItemIndex={initialTopMostItemIndex}
        // All follow requests go through the same cancellable controller.
        followOutput={false}
        scrollerRef={scrollerRef}
        totalListHeightChanged={onListHeightChanged}
        style={{ overflowAnchor: "none" }}
        tabIndex={0}
        aria-label="聊天消息"
        rangeChanged={handleRangeChanged}
        className="h-full"
        components={{ List, Footer }}
        itemContent={(index, message) => (
          <div
            className={cn(index === messages.length - 1 && "animate-fade-up")}
            onMouseDown={handleTripleClickDown}
          >
            <MessageItem message={message} isLast={index === messages.length - 1} />
          </div>
        )}
      />
      {scroll.hasOverflow && (
        <div
          className="absolute bottom-4 right-4 z-20 flex flex-col items-end gap-2"
          role="group"
          aria-label="消息滚动导航"
        >
          <Button
            type="button"
            variant="outline"
            className="h-10 bg-white shadow-sm"
            onClick={handleScrollToTop}
            disabled={scroll.atTop}
            aria-label="返回到最上面"
            title="返回到最上面"
          >
            <ArrowUpToLine className="h-4 w-4" aria-hidden="true" />
            <span className="hidden sm:inline">返回到最上面</span>
          </Button>
          <div className="flex items-center gap-2">
            {scroll.hasNewContent && (
              <span
                className="rounded-full border border-border bg-white px-3 py-1 text-xs text-muted-foreground shadow-sm"
                role="status"
              >
                {isStreaming ? "新内容生成中" : "有新内容"}
              </span>
            )}
            <Button
              type="button"
              variant="outline"
              className="h-10 bg-white shadow-sm"
              onClick={scrollToBottom}
              disabled={scroll.atBottom && scroll.following}
              aria-label="返回到最下面"
              title="返回到最下面"
            >
              <ArrowDownToLine className="h-4 w-4" aria-hidden="true" />
              <span className="hidden sm:inline">返回到最下面</span>
            </Button>
          </div>
        </div>
      )}
      <QuestionRail
        items={userQuestions}
        activeId={activeQuestionId}
        onSelect={handleSelectQuestion}
      />
    </div>
  );
}

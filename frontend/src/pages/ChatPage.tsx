import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { MessageList } from "@/components/chat/MessageList";
import { MainLayout } from "@/components/layout/MainLayout";
import { useChatStore } from "@/stores/chatStore";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    fetchSessions,
    selectSession,
    createSession
  } = useChatStore();
  const showWelcome = messages.length === 0 && !isLoading;
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  React.useEffect(() => {
    let active = true;
    fetchSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [fetchSessions]);

  React.useEffect(() => {
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        createSession().catch(() => null);
        navigate("/chat", { replace: true });
        return;
      }
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    selectSession,
    createSession,
    navigate
  ]);

  // 流式 meta 解析出新会话后同步地址栏。
  // 不用「对比 currentSessionId 与 sessionId 再 navigate」的 effect：它与上面的会话加载
  // effect 会在同一轮提交里各拿各的旧值互相纠偏，形成两个会话间 ~45 次/秒的导航死循环
  // （表现为新对话不停闪烁）。订阅只在 currentSessionId 真正变化时触发一次导航，
  // URL 已是目标会话时短路，不会与 selectSession 形成环。
  React.useEffect(() => {
    return useChatStore.subscribe((state, prevState) => {
      const nextId = state.currentSessionId;
      if (!nextId || nextId === prevState.currentSessionId) {
        return;
      }
      if (window.location.pathname === `/chat/${nextId}`) {
        return;
      }
      navigate(`/chat/${nextId}`, { replace: true });
    });
  }, [navigate]);

  return (
    <MainLayout>
      <div className="flex h-full flex-col bg-white">
        <div className="flex-1 min-h-0">
          <MessageList
            messages={messages}
            isLoading={isLoading}
            isStreaming={isStreaming}
            sessionKey={currentSessionId}
          />
        </div>
        {showWelcome ? null : (
          <div className="relative z-20 bg-white">
            <div className="mx-auto max-w-[840px] px-6 pt-1 pb-4">
              <ChatInput />
            </div>
          </div>
        )}
      </div>
    </MainLayout>
  );
}

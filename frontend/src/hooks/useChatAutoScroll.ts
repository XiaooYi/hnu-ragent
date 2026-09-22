import * as React from "react";

import { createChatAutoScroll, initialChatScrollState } from "@/lib/chatAutoScroll";

interface ChatAutoScrollProps {
  sessionKey?: string | null;
  latestUserId?: string;
  content?: string;
  thinking?: string;
  isLoading: boolean;
}

export function useChatAutoScroll({
  sessionKey,
  latestUserId,
  content,
  thinking,
  isLoading
}: ChatAutoScrollProps) {
  const viewportRef = React.useRef<HTMLElement | null>(null);
  const [viewport, setViewport] = React.useState<HTMLElement | null>(null);
  const [state, setState] = React.useState(initialChatScrollState);
  const previousUserRef = React.useRef(latestUserId);
  const controller = React.useMemo(
    () =>
      createChatAutoScroll({
        getViewport: () => viewportRef.current,
        scrollToBottom: () => {
          const node = viewportRef.current;
          if (node) node.scrollTop = node.scrollHeight;
        },
        requestFrame: (callback) => window.requestAnimationFrame(callback),
        cancelFrame: (id) => window.cancelAnimationFrame(id),
        onChange: (next) =>
          setState((previous) =>
            previous.following === next.following &&
            previous.hasNewContent === next.hasNewContent &&
            previous.hasOverflow === next.hasOverflow &&
            previous.atTop === next.atTop &&
            previous.atBottom === next.atBottom
              ? previous
              : next
          )
      }),
    []
  );

  const scrollerRef = React.useCallback((node: HTMLElement | Window | null) => {
    const element = node instanceof HTMLElement ? node : null;
    viewportRef.current = element;
    setViewport(element);
  }, []);

  React.useLayoutEffect(() => {
    controller.reset();
    return controller.dispose;
  }, [controller, sessionKey]);

  React.useLayoutEffect(() => {
    if (isLoading) return;
    if (previousUserRef.current !== latestUserId) {
      previousUserRef.current = latestUserId;
      controller.resume();
    }
    controller.contentChanged();
  }, [controller, latestUserId, content, thinking, isLoading]);

  React.useLayoutEffect(() => {
    if (!viewport) return;
    let touchY: number | null = null;
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY < 0) controller.pause();
    };
    const onTouchStart = (event: TouchEvent) => {
      touchY = event.touches[0]?.clientY ?? null;
    };
    const onTouchMove = (event: TouchEvent) => {
      const nextY = event.touches[0]?.clientY ?? null;
      if (nextY !== null && touchY !== null && nextY > touchY) controller.pause();
      touchY = nextY;
    };
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (target.closest("input, textarea, select, [contenteditable=true]")) return;
      if (
        ["ArrowUp", "PageUp", "Home"].includes(event.key) ||
        (event.key === " " && event.shiftKey)
      ) {
        controller.pause();
      }
    };
    viewport.addEventListener("scroll", controller.onScroll, { passive: true });
    viewport.addEventListener("wheel", onWheel, { passive: true });
    viewport.addEventListener("touchstart", onTouchStart, { passive: true });
    viewport.addEventListener("touchmove", onTouchMove, { passive: true });
    viewport.addEventListener("keydown", onKeyDown);
    const observer = new ResizeObserver(controller.layoutChanged);
    observer.observe(viewport);
    window.addEventListener("resize", controller.layoutChanged);
    controller.measure();
    controller.layoutChanged();
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", controller.layoutChanged);
      viewport.removeEventListener("scroll", controller.onScroll);
      viewport.removeEventListener("wheel", onWheel);
      viewport.removeEventListener("touchstart", onTouchStart);
      viewport.removeEventListener("touchmove", onTouchMove);
      viewport.removeEventListener("keydown", onKeyDown);
    };
  }, [controller, viewport]);

  return {
    ...state,
    scrollerRef,
    pause: controller.pause,
    scrollToBottom: controller.resume,
    onListHeightChanged: controller.layoutChanged
  };
}

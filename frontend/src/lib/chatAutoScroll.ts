export interface ChatScrollState {
  following: boolean;
  hasNewContent: boolean;
  hasOverflow: boolean;
  atTop: boolean;
  atBottom: boolean;
}

interface Viewport {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
}

interface ChatAutoScrollOptions {
  getViewport: () => Viewport | null;
  scrollToBottom: () => void;
  requestFrame: (callback: () => void) => number;
  cancelFrame: (id: number) => void;
  onChange: (state: ChatScrollState) => void;
}

export const initialChatScrollState: ChatScrollState = {
  following: true,
  hasNewContent: false,
  hasOverflow: false,
  atTop: true,
  atBottom: true
};

export function createChatAutoScroll(options: ChatAutoScrollOptions) {
  const threshold = 24;
  let state = { ...initialChatScrollState };
  let frameId: number | null = null;
  let disposed = false;
  let lastScrollTop = options.getViewport()?.scrollTop ?? 0;
  let lastClientHeight = options.getViewport()?.clientHeight ?? 0;

  const readGeometry = () => {
    const viewport = options.getViewport();
    if (!viewport) return { hasOverflow: false, atTop: true, atBottom: true };
    const maxScrollTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    return {
      hasOverflow: maxScrollTop > 1,
      atTop: viewport.scrollTop <= 1,
      atBottom: maxScrollTop <= 1 || maxScrollTop - viewport.scrollTop <= threshold
    };
  };

  const emit = (changes: Partial<ChatScrollState> = {}) => {
    state = { ...state, ...readGeometry(), ...changes };
    options.onChange({ ...state });
  };

  const cancelPending = () => {
    if (frameId === null) return;
    options.cancelFrame(frameId);
    frameId = null;
  };

  const scheduleBottom = () => {
    if (disposed || !state.following || frameId !== null) return;
    frameId = options.requestFrame(() => {
      frameId = null;
      if (disposed || !state.following) return;
      options.scrollToBottom();
      lastScrollTop = options.getViewport()?.scrollTop ?? 0;
      emit({ hasNewContent: false, following: true });
    });
  };

  const measure = () => {
    lastScrollTop = options.getViewport()?.scrollTop ?? 0;
    lastClientHeight = options.getViewport()?.clientHeight ?? 0;
    emit();
  };
  const layoutChanged = () => {
    emit();
    if (state.following) scheduleBottom();
  };

  return {
    measure,
    layoutChanged,
    contentChanged: () => {
      if (state.following) {
        scheduleBottom();
      } else {
        emit({ hasNewContent: true });
      }
    },
    onScroll: () => {
      const viewport = options.getViewport();
      const top = viewport?.scrollTop ?? 0;
      const clientHeight = viewport?.clientHeight ?? 0;
      const resized = clientHeight !== lastClientHeight;
      lastClientHeight = clientHeight;
      const direction = top - lastScrollTop;
      lastScrollTop = top;
      // A tiny upward movement must pause even inside the bottom threshold.
      // Height changes alone must never switch the reader back to following.
      if (resized) {
        // Browsers clamp scrollTop when the viewport expands.
        emit();
        scheduleBottom();
      } else if (direction < 0) {
        cancelPending();
        emit({ following: false });
      } else if (direction > 0 && readGeometry().atBottom) {
        emit({ following: true, hasNewContent: false });
      } else {
        emit();
      }
    },
    pause: () => {
      cancelPending();
      emit({ following: false });
    },
    resume: () => {
      emit({ following: true, hasNewContent: false });
      scheduleBottom();
    },
    reset: () => {
      disposed = false;
      cancelPending();
      lastScrollTop = options.getViewport()?.scrollTop ?? 0;
      emit({ following: true, hasNewContent: false });
      scheduleBottom();
    },
    dispose: () => {
      disposed = true;
      cancelPending();
    }
  };
}

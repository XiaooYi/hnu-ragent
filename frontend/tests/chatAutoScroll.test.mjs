import assert from "node:assert/strict";
import { test } from "node:test";
import { build } from "esbuild";

const result = await build({
  entryPoints: ["src/lib/chatAutoScroll.ts"],
  bundle: true,
  write: false,
  format: "esm",
  platform: "node"
});
const { createChatAutoScroll } = await import(
  `data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`
);

// Only the browser's geometry and frame clock are simulated. The production
// controller decides whether the viewport moves as more content arrives.
function setup() {
  const viewport = { scrollTop: 600, scrollHeight: 1000, clientHeight: 400 };
  const frames = new Map();
  let nextFrame = 0;
  let state;
  const controller = createChatAutoScroll({
    getViewport: () => viewport,
    scrollToBottom: () => {
      viewport.scrollTop = viewport.scrollHeight - viewport.clientHeight;
    },
    requestFrame: (callback) => {
      frames.set(++nextFrame, callback);
      return nextFrame;
    },
    cancelFrame: (id) => frames.delete(id),
    onChange: (value) => {
      state = value;
    }
  });
  controller.measure();
  const flush = () => {
    const pending = [...frames.values()];
    frames.clear();
    pending.forEach((callback) => callback());
  };
  return { controller, viewport, frames, flush, state: () => state };
}

test("growing streamed answer follows the bottom and batches frames", () => {
  const h = setup();
  h.viewport.scrollHeight = 1200;
  h.controller.contentChanged();
  h.viewport.scrollHeight = 1400;
  h.controller.contentChanged();
  assert.equal(h.frames.size, 1);
  h.flush();
  assert.equal(h.viewport.scrollTop, 1000);
  assert.equal(h.state().hasNewContent, false);
});

test("upward input cancels pending scrolling even within the bottom threshold", () => {
  const h = setup();
  h.controller.contentChanged();
  h.controller.pause(); // wheel/touch/key intent is received before scroll
  h.viewport.scrollTop = 596;
  h.controller.onScroll();
  h.viewport.scrollHeight = 1400;
  h.controller.contentChanged();
  h.flush();
  assert.equal(h.viewport.scrollTop, 596);
  assert.equal(h.state().following, false);
  assert.equal(h.state().hasNewContent, true);
});

test("scrollbar drag pauses following and late layout or completion cannot pull it back", () => {
  const h = setup();
  h.viewport.scrollTop = 200;
  h.controller.onScroll();
  h.viewport.scrollHeight = 1600;
  h.controller.contentChanged();
  h.controller.layoutChanged(); // includes delayed image/font/final rendering
  h.flush();
  assert.equal(h.viewport.scrollTop, 200);
  assert.equal(h.state().following, false);
});

test("scrolling down into the bottom threshold resumes following", () => {
  const h = setup();
  h.viewport.scrollTop = 100;
  h.controller.onScroll();
  h.viewport.scrollHeight = 1400;
  h.controller.contentChanged();
  h.viewport.scrollTop = 990;
  h.controller.onScroll();
  h.viewport.scrollHeight = 1600;
  h.controller.contentChanged();
  h.flush();
  assert.equal(h.viewport.scrollTop, 1200);
  assert.equal(h.state().hasNewContent, false);
});

test("jumping to top pauses until an explicit bottom jump resumes", () => {
  const h = setup();
  h.controller.pause();
  h.viewport.scrollTop = 0;
  h.controller.onScroll();
  h.viewport.scrollHeight = 1500;
  h.controller.contentChanged();
  h.flush();
  assert.equal(h.viewport.scrollTop, 0);
  assert.equal(h.state().atTop, true);
  h.controller.resume();
  h.flush();
  assert.equal(h.viewport.scrollTop, 1100);
  assert.equal(h.state().following, true);
  assert.equal(h.state().hasNewContent, false);
});

test("layout measurements alone do not resume a paused reader or report new text", () => {
  const h = setup();
  h.controller.pause();
  h.controller.layoutChanged();
  h.flush();
  assert.equal(h.state().following, false);
  assert.equal(h.state().hasNewContent, false);
});

test("new conversation reset clears unread state and resumes at the bottom", () => {
  const h = setup();
  h.controller.pause();
  h.viewport.scrollTop = 0;
  h.controller.contentChanged();
  h.controller.reset();
  h.flush();
  assert.equal(h.viewport.scrollTop, 600);
  assert.equal(h.state().following, true);
  assert.equal(h.state().hasNewContent, false);
});

test("short conversations have no scroll navigation and disposal cancels work", () => {
  const h = setup();
  h.viewport.scrollHeight = 300;
  h.viewport.scrollTop = 0;
  h.controller.measure();
  assert.equal(h.state().hasOverflow, false);
  assert.equal(h.state().atTop, true);
  assert.equal(h.state().atBottom, true);
  h.controller.contentChanged();
  h.controller.dispose();
  assert.equal(h.frames.size, 0);
});
test("resizing a bottom-aligned viewport does not look like an upward user scroll", () => {
  const h = setup();
  h.viewport.clientHeight = 800;
  h.viewport.scrollTop = 200; // browser repositions the bottom edge during resize
  h.controller.onScroll();
  h.viewport.scrollHeight = 1300;
  h.controller.layoutChanged();
  h.flush();
  assert.equal(h.viewport.scrollTop, 500);
  assert.equal(h.state().following, true);
});

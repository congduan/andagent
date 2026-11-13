import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";
import { ToolCard } from "./ToolCard";

/** ToolCallMessagePartProps 字段很多，ToolCard 只用到其中几个，构造最小夹具。 */
function props(overrides: Record<string, unknown> = {}): ToolCallMessagePartProps {
  return {
    toolName: "get_time",
    toolCallId: "call-1",
    args: {},
    result: "2026-11-13 10:00:00",
    isError: false,
    ...overrides,
  } as unknown as ToolCallMessagePartProps;
}

type TtsHost = { AndroidBridge?: unknown };
const host = window as unknown as TtsHost;

// R150-review 建议⑤：按钮文案语言无关断言（中英各两种态，测试环境语言不确定）。
const KNOWN_TTS_LABELS = ["Read result aloud", "朗读结果", "Stop reading", "停止朗读"];

/** R150: 直接布置一个带 kvTts* 的 AndroidBridge，验证 bridge.ts 包装是否被卡片调用。 */
function installTtsBridge() {
  const speak = vi.fn((_text: string) => "OK: speaking");
  const stop = vi.fn(() => "OK: stopped");
  // R150-review 建议⑤-1：state mock 用可变对象，测试可翻转原生 speaking 模拟播放/结束。
  const tts = { speaking: false };
  const state = vi.fn(() => JSON.stringify({ available: true, speaking: tts.speaking }));
  host.AndroidBridge = { kvTtsSpeak: speak, kvTtsStop: stop, kvTtsState: state };
  return { speak, stop, tts };
}

afterEach(() => {
  cleanup();
  delete host.AndroidBridge;
  vi.restoreAllMocks();
});

describe("ToolCard 朗读结果 (R150)", () => {
  it("结果非空且非 running 时显示朗读按钮，点击把格式化结果文本送 kvTtsSpeak", () => {
    const { speak } = installTtsBridge();
    const view = render(<ToolCard {...props()} />);
    const button = view.container.querySelector("button.tool-speak");
    expect(button).toBeTruthy();
    fireEvent.click(button!);
    expect(speak).toHaveBeenCalledTimes(1);
    expect(speak.mock.calls[0][0]).toContain("2026-11-13");
  });

  it("播报中再点变停止，调用 kvTtsStop 而不是再次 speak", () => {
    const { speak, stop } = installTtsBridge();
    const view = render(<ToolCard {...props()} />);
    fireEvent.click(view.container.querySelector("button.tool-speak")!);
    expect(stop).not.toHaveBeenCalled();
    fireEvent.click(view.container.querySelector("button.tool-speak")!);
    expect(stop).toHaveBeenCalledTimes(1);
    expect(speak).toHaveBeenCalledTimes(1);
  });

  it("running（结果为空）时不显示朗读按钮", () => {
    installTtsBridge();
    const view = render(<ToolCard {...props({ result: undefined })} />);
    expect(view.container.querySelector("button.tool-speak")).toBeNull();
  });

  it("播报自然结束后（ttsState.speaking=false）按钮态回落为朗读（R150-review 必修②/建议⑤）", () => {
    vi.useFakeTimers();
    try {
      const { speak, tts } = installTtsBridge();
      const view = render(<ToolCard {...props()} />);
      const button = () => view.container.querySelector("button.tool-speak")!;
      fireEvent.click(button());
      expect(speak).toHaveBeenCalledTimes(1);
      // 模拟原生进入播放态：一次 800ms 轮询后 owner 仍保持「停止」。
      tts.speaking = true;
      act(() => { vi.advanceTimersByTime(900); });
      const stopLabel = button().textContent!.trim();
      expect(stopLabel.toLowerCase()).toContain("stop");
      expect(KNOWN_TTS_LABELS).toContain(stopLabel);
      // 模拟原生播报自然结束：下一次轮询后回落为「朗读」，文案翻转。
      tts.speaking = false;
      act(() => { vi.advanceTimersByTime(900); });
      const idleLabel = button().textContent!.trim();
      expect(idleLabel.toLowerCase()).not.toContain("stop");
      expect(KNOWN_TTS_LABELS).toContain(idleLabel);
      expect(idleLabel).not.toBe(stopLabel);
    } finally {
      vi.useRealTimers();
    }
  });

  it("跨卡抢占：B 抢占后 A 回落且 A 卸载不误停 B，仅 owner 卸载时 stop（R150-review 必修③）", () => {
    vi.useFakeTimers();
    try {
      const { speak, stop, tts } = installTtsBridge();
      const a = render(<ToolCard {...props({ toolCallId: "call-A" })} />);
      const b = render(<ToolCard {...props({ toolCallId: "call-B" })} />);
      const aBtn = () => a.container.querySelector("button.tool-speak")!;
      const bBtn = () => b.container.querySelector("button.tool-speak")!;
      // A 开始播报。
      fireEvent.click(aBtn());
      expect(speak).toHaveBeenCalledTimes(1);
      // 原生进入播放态。
      tts.speaking = true;
      // B 点击朗读即抢占（Kotlin QUEUE_FLUSH），走 speak 而非 stop。
      fireEvent.click(bBtn());
      expect(speak).toHaveBeenCalledTimes(2);
      expect(stop).not.toHaveBeenCalled();
      // 轮询：原生仍 speaking=true，但 A 已非全局 owner → 回落为「朗读」；B 保持 owner。
      act(() => { vi.advanceTimersByTime(900); });
      expect(aBtn().textContent!.trim().toLowerCase()).not.toContain("stop");
      // A 卸载：非 owner，不打断 B 的播放。
      a.unmount();
      expect(stop).not.toHaveBeenCalled();
      // B 卸载：唯一 owner，停止播报。
      b.unmount();
      expect(stop).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render } from "@testing-library/react";
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

/** R150: 直接布置一个带 kvTts* 的 AndroidBridge，验证 bridge.ts 包装是否被卡片调用。 */
function installTtsBridge() {
  const speak = vi.fn((_text: string) => "OK: speaking");
  const stop = vi.fn(() => "OK: stopped");
  const state = vi.fn(() => JSON.stringify({ available: true, speaking: false }));
  host.AndroidBridge = { kvTtsSpeak: speak, kvTtsStop: stop, kvTtsState: state };
  return { speak, stop, state };
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
});

import { beforeEach, describe, expect, it } from "vitest";
import { AgentEngine, screencapImageFence, toUiMessages } from "./agent";
import { DEFAULT_SETTINGS } from "./bridge";

// R105: the cron a2ui consumption loop — inbox -> consumeA2uiForms() ->
// appendA2uiForm() -> assistant message with the ```a2ui fence. Submitting the
// card later returns via composer-fill (tested in A2uiCard.test.tsx).

const FENCE = '```a2ui\n{"title":"晨报","fields":[{"type":"SWITCH","key":"todo","prefill":true}]}\n```';

type UiMsg = {
  role: string;
  content: Array<{ type: string; text?: string }>;
  metadata?: { custom?: { usage?: { input: number; output: number } } };
};

const messagesOf = (engine: AgentEngine): UiMsg[] =>
  engine.getSnapshot().messages as unknown as UiMsg[];

beforeEach(() => {
  localStorage.clear();
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
});

describe("AgentEngine · cron a2ui form injection", () => {
  it("injects a deterministic assistant message (no LLM round-trip)", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    engine.appendA2uiForm(FENCE);

    const msgs = messagesOf(engine);
    expect(msgs).toHaveLength(1);
    expect(msgs[0].role).toBe("assistant");
    // toUiMessages keeps a plain text part verbatim; the fence renders via Markdown -> A2uiCard.
    expect(msgs[0].content).toEqual([{ type: "text", text: FENCE }]);
    // ZERO_USAGE: a cron form must never be billed as model tokens.
    expect(msgs[0].metadata?.custom?.usage).toEqual({ input: 0, output: 0 });
  });

  it("ignores a blank fence", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    engine.appendA2uiForm("   \n  ");
    expect(messagesOf(engine)).toHaveLength(0);
  });

  it("isRunning is false when idle and guards injection during a running turn", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    expect(engine.isRunning).toBe(false);
    // App.tsx gates the durable inbox drain on this getter; as a defense in
    // depth appendA2uiForm itself still refuses to splice into a running turn.
    (engine as unknown as { running: boolean }).running = true;
    expect(engine.isRunning).toBe(true);
    engine.appendA2uiForm(FENCE);
    expect(messagesOf(engine)).toHaveLength(0);
  });
});

// R136: adb_screencap 成功结果 → 自动出一张纯展示 IMAGE a2ui 卡。
describe("AgentEngine · R136 screencap → IMAGE a2ui 卡", () => {
  const screencapResult = JSON.stringify({
    savedPath: "/sdcard/Pictures/AndAgent/shot.png",
    thumbnailBase64: "iVBORw0KGgoAAAANSUhEUg==",
    thumbnailMime: "image/png",
    width: 1080,
    height: 2400,
  });

  const assistant = (content: unknown[]) =>
    ({
      role: "assistant",
      content,
      usage: { input: 1, output: 1 },
      stopReason: "toolUse",
      timestamp: 1000,
    }) as never;

  const toolResult = (text: string, isError: boolean) =>
    ({
      role: "toolResult",
      toolCallId: "sc1",
      toolName: "adb_screencap",
      content: [{ type: "text", text }],
      isError,
      timestamp: 1001,
    }) as never;

  const screencapCall = { type: "toolCall", id: "sc1", name: "adb_screencap", arguments: { target: "127.0.0.1:5555" } };
  const imageTextParts = (ui: ReturnType<typeof toUiMessages>) => {
    const last = ui[ui.length - 1] as unknown as UiMsg;
    return last.content.filter((p) => p.type === "text" && p.text?.includes('"IMAGE"'));
  };

  it("screencapImageFence 从成功结果生成纯展示 IMAGE 围栏", () => {
    const fence = screencapImageFence("adb_screencap", screencapResult, false);
    expect(fence).toBeTruthy();
    expect(fence).toContain("```a2ui");
    expect(fence).toContain('"IMAGE"');
    expect(fence).toContain("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==");
    const json = JSON.parse(fence!.replace(/```a2ui\n|\n```/g, ""));
    expect(json.fields).toHaveLength(1);
    expect(json.fields[0].type).toBe("IMAGE");
  });

  it("screencapImageFence 拒绝非 screencap / 失败 / 无图，兼容裸 base64", () => {
    expect(screencapImageFence("get_time", screencapResult, false)).toBeNull();
    expect(screencapImageFence("adb_screencap", screencapResult, true)).toBeNull();
    expect(screencapImageFence("adb_screencap", "not json", false)).toBeNull();
    expect(screencapImageFence("adb_screencap", JSON.stringify({ savedPath: "x" }), false)).toBeNull();
    expect(screencapImageFence("adb_screencap", JSON.stringify({ base64: "AAAA" }), false)).toContain('"IMAGE"');
  });

  it("toUiMessages 为 screencap 成功自动注入一张 IMAGE a2ui 卡", () => {
    const ui = toUiMessages([assistant([screencapCall]), toolResult(screencapResult, false)], null);
    expect(imageTextParts(ui)).toHaveLength(1);
    expect(imageTextParts(ui)[0].text).toContain("```a2ui");
  });

  it("失败结果不注入；模型已有 IMAGE a2ui 卡时保留且不重复出卡", () => {
    const failed = toUiMessages(
      [assistant([screencapCall]), toolResult("ERROR: timeout", true)],
      null,
    );
    expect(imageTextParts(failed)).toHaveLength(0);

    const ownCard = '```a2ui\n{"fields":[{"type":"IMAGE","src":"data:image/png;base64,AAAA"}]}\n```';
    const withOwn = toUiMessages(
      [
        assistant([{ type: "text", text: ownCard }, screencapCall]),
        toolResult(screencapResult, false),
      ],
      null,
    );
    // 仍是模型自己那张，注入器不再重复出卡。
    expect(imageTextParts(withOwn)).toHaveLength(1);
    expect(imageTextParts(withOwn)[0].text).toBe(ownCard);
  });
});

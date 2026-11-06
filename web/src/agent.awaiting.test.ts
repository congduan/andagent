import { describe, expect, it } from "vitest";
import { toUiMessages } from "./agent";

const assistant = (content: unknown[], stopReason = "toolUse") =>
  ({
    role: "assistant",
    content,
    stopReason,
    usage: { input: 1, output: 1 },
    timestamp: 1000,
  }) as never;

const toolResult = {
  role: "toolResult",
  toolCallId: "t1",
  toolName: "get_time",
  content: [{ type: "text", text: "ok" }],
  isError: false,
  timestamp: 1001,
} as never;

const conversation = [
  assistant([{ type: "toolCall", id: "t1", name: "get_time", arguments: {} }]),
  toolResult,
] as never;

const statusOf = (message: unknown) => (message as { status?: unknown }).status;
const partsOf = (message: unknown) => (message as { content: Array<Record<string, unknown>> }).content;

describe("toUiMessages · 等待模型续写", () => {
  it("完成消息不写死 status，交给 assistant-ui 按 isLast + running 推断", () => {
    const ui = toUiMessages(conversation, null);
    // 写死 complete 会压住 assistant-ui 的自动状态，工具执行完等下一段回复时
    // 就没有等待指示。
    expect(statusOf(ui[ui.length - 1])).toBeUndefined();
  });

  it("工具结果折叠进工具卡片，尾部仍是工具调用", () => {
    const ui = toUiMessages(conversation, null);
    const parts = partsOf(ui[ui.length - 1]);
    expect(parts[0].type).toBe("tool-call");
    expect(parts[0].result).toBe("ok");
  });

  it("中断/出错的助手消息保留 incomplete 状态", () => {
    const aborted = [
      assistant([{ type: "toolCall", id: "t1", name: "get_time", arguments: {} }], "aborted"),
    ] as never;
    const ui = toUiMessages(aborted, null);
    expect(statusOf(ui[ui.length - 1])).toEqual({ type: "incomplete", reason: "cancelled" });
  });

  it("流式中的 draft 消息保持 running", () => {
    const ui = toUiMessages(conversation, { text: "", thinking: "", tools: [] });
    expect(ui[ui.length - 1].id).toBe("live");
    expect(statusOf(ui[ui.length - 1])).toEqual({ type: "running" });
  });
});

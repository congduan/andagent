import { beforeEach, describe, expect, it, vi } from "vitest";
import { AgentEngine } from "./agent";
import { SessionStore } from "./sessions";
import { DEFAULT_SETTINGS } from "./bridge";
import type { AssistantMessage, UserMessage } from "@mariozechner/pi-ai";

// R82：编辑重发 / 重试本轮 = 截断目标用户消息及其之后的历史，再重新发起一轮。

const user = (text: string): UserMessage => ({ role: "user", content: text, timestamp: Date.now() });
const assistant = (text: string): AssistantMessage => ({
  role: "assistant",
  content: [{ type: "text", text }],
  api: "openai-completions",
  provider: "custom",
  model: "test",
  usage: {
    input: 1,
    output: 1,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 2,
    cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  },
  stopReason: "stop",
  timestamp: Date.now(),
});

let replyText = "新回复";
vi.mock("./pi", () => ({
  loadPi: async () => ({
    stream: () => ({
      async *[Symbol.asyncIterator]() {
        yield { type: "text_delta", delta: replyText };
      },
      result: async () => assistant(replyText),
    }),
  }),
}));

const testSettings = (): typeof DEFAULT_SETTINGS => ({
  ...DEFAULT_SETTINGS,
  baseUrl: "http://127.0.0.1:8000/v1",
  apiKey: "test-key",
  model: "test-model",
});

/** 用已有会话构造引擎，并返回该会话里第 n 条用户消息的 UI id。 */
function setup(messages: (UserMessage | AssistantMessage)[], sessionId = "s_edit") {
  SessionStore.save(sessionId, messages);
  const agent = new AgentEngine(testSettings());
  agent.switchSession(sessionId);
  return agent;
}

const userUiIds = (agent: AgentEngine): string[] =>
  agent
    .getSnapshot()
    .messages.filter((m) => m.role === "user")
    .map((m) => m.id ?? "");

const texts = (messages: { role: string; content: unknown }[]): string[] =>
  messages.map((m) =>
    typeof m.content === "string"
      ? m.content
      : (m.content as { type: string; text?: string }[])
          .filter((p) => p.type === "text")
          .map((p) => p.text ?? "")
          .join(""),
  );

beforeEach(() => {
  localStorage.clear();
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
  replyText = "新回复";
});

describe("R82: 编辑重发", () => {
  it("截断被编辑消息及其之后的全部历史，并用新文本重发", async () => {
    const agent = setup([user("第一问"), assistant("第一答"), user("第二问"), assistant("第二答")]);
    const ids = userUiIds(agent);
    expect(ids).toHaveLength(2);

    const ok = await agent.editAndResend(ids[1], "第二问（改）");
    expect(ok).toBe(true);

    const stored = SessionStore.load("s_edit");
    expect(texts(stored)).toEqual(["第一问", "第一答", "第二问（改）", "新回复"]);
    // 被编辑的消息带「已编辑」标记，且随会话持久化。
    expect((stored[2] as { edited?: boolean }).edited).toBe(true);
    expect((stored[0] as { edited?: boolean }).edited).toBeUndefined();
  });

  it("编辑首条用户消息：其后的全部历史被清空", async () => {
    const agent = setup([user("第一问"), assistant("第一答"), user("第二问"), assistant("第二答")]);
    const ok = await agent.editAndResend(userUiIds(agent)[0], "改后的第一问");
    expect(ok).toBe(true);
    expect(texts(SessionStore.load("s_edit"))).toEqual(["改后的第一问", "新回复"]);
  });

  it("重试本轮：保留原文本，不带「已编辑」标记", async () => {
    const agent = setup([user("第一问"), assistant("第一答")]);
    const ok = await agent.retryFrom(userUiIds(agent)[0]);
    expect(ok).toBe(true);
    const stored = SessionStore.load("s_edit");
    expect(texts(stored)).toEqual(["第一问", "新回复"]);
    expect((stored[0] as { edited?: boolean }).edited).toBeUndefined();
  });

  it("目标 id 非法或不是用户消息时不改动历史", async () => {
    const agent = setup([user("第一问"), assistant("第一答")]);
    const before = texts(SessionStore.load("s_edit"));
    const assistantId = agent.getSnapshot().messages.find((m) => m.role === "assistant")?.id ?? "";
    expect(await agent.editAndResend("u-999-1", "x")).toBe(false);
    expect(await agent.retryFrom(assistantId)).toBe(false);
    expect(await agent.editAndResend(userUiIds(agent)[0], "   ")).toBe(false);
    expect(texts(SessionStore.load("s_edit"))).toEqual(before);
  });

  it("截断后会话 token 统计同步重算", async () => {
    const agent = setup([
      user("第一问"),
      assistant("第一答"),
      user("第二问"),
      assistant("第二答"),
      user("第三问"),
      assistant("第三答"),
    ]);
    expect(agent.getSnapshot().usage.session.output).toBe(3);
    // 编辑首条消息：前三条回复全部被截断，只剩新一轮的 output=1。
    await agent.editAndResend(userUiIds(agent)[0], "改后的第一问");
    expect(agent.getSnapshot().usage.session.output).toBe(1);
  });
});

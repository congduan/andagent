import { beforeEach, describe, expect, it, vi } from "vitest";
import { AgentEngine } from "./agent";
import { SessionStore } from "./sessions";
import { DEFAULT_SETTINGS } from "./bridge";
import type { AssistantMessage, UserMessage } from "@mariozechner/pi-ai";

// R93 review 必修项 1：生成中删除/切换当前会话时，旧轮次不得把回复写进切换后的会话。

const user = (text: string): UserMessage => ({ role: "user", content: text, timestamp: Date.now() });
const assistant = (text: string): AssistantMessage => ({
  role: "assistant",
  content: [{ type: "text", text }],
  api: "openai-completions",
  provider: "custom",
  model: "test",
  usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } },
  stopReason: "stop",
  timestamp: Date.now(),
});

let agentRef: AgentEngine | null = null;
let resolveTurn: (() => void) | null = null;
const waitGate = (): Promise<void> =>
  new Promise((r) => {
    resolveTurn = r;
  });

/** fake pi：progress 携带 initialMessages 后停在闸门，等测试操作会话再放行 final。 */
vi.mock("./pi", () => ({
  loadPi: async () => ({
    stream: () => ({
      async *[Symbol.asyncIterator]() {
        // messages 是私有字段，测试经 snapshot 读取
        yield { type: "progress", initialMessages: agentRef!.getSnapshot().messages };
        await waitGate();
        yield { type: "final", message: assistant("旧轮回复") };
      },
      // agent.ts 在事件循环结束后调 stream.result() 取最终消息
      result: async () => assistant("旧轮回复"),
    }),
  }),
}));

beforeEach(() => {
  localStorage.clear();
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
  agentRef = null;
  resolveTurn = null;
});

const hasText = (msgs: { role: string; content: unknown }[], role: string, text: string): boolean =>
  msgs.some(
    (m) =>
      m.role === role &&
      (typeof m.content === "string"
        ? m.content === text
        : Array.isArray(m.content) && m.content.some((p) => (p as { type: string; text?: string }).type === "text" && (p as { text?: string }).text === text)),
  );

const testSettings = (): typeof DEFAULT_SETTINGS => ({
  ...DEFAULT_SETTINGS,
  baseUrl: "http://127.0.0.1:8000/v1",
  apiKey: "test-key",
  model: "test-model",
});

describe("R93: 生成中删除/切换会话的隔离", () => {
  it("生成中批量删除当前会话并切到剩余会话：旧轮回复不写入新会话", async () => {
    SessionStore.save("s_old", [user("问题一"), assistant("回答一")]);
    SessionStore.save("s_keep", [user("保留会话消息")]);
    const agent = new AgentEngine(testSettings());
    agentRef = agent;
    // switchSession 会把构造时的空白初始会话 flush 进索引，一并删除
    const initialId = (agent as unknown as { sessionId: string }).sessionId;
    agent.switchSession("s_old");

    const sendPromise = agent.send("继续问");
    await vi.waitFor(() => expect((agent as unknown as { running: boolean }).running).toBe(true));
    await vi.waitFor(() => expect(resolveTurn).toBeTypeOf("function"));

    // 模型流式期间：批量删除当前会话（含当前），引擎切到 s_keep
    agent.deleteSessions([initialId, "s_old"]);
    expect((agent as unknown as { sessionId: string }).sessionId).toBe("s_keep");

    resolveTurn!();
    await sendPromise;
    expect((agent as unknown as { running: boolean }).running).toBe(false);

    const kept = SessionStore.load("s_keep");
    expect(hasText(kept, "assistant", "旧轮回复")).toBe(false);
    expect(hasText(kept, "user", "继续问")).toBe(false);
    expect(kept.map((m) => m.content)).toEqual(["保留会话消息"]);
  });

  it("生成中切换到其他会话（不删除）：旧轮回复不写入新会话", async () => {
    SessionStore.save("s_a", [user("A 问")]);
    SessionStore.save("s_b", [user("B 问")]);
    const agent = new AgentEngine(testSettings());
    agentRef = agent;
    const initialId = (agent as unknown as { sessionId: string }).sessionId;
    agent.switchSession("s_a");

    const sendPromise = agent.send("A 继续问");
    await vi.waitFor(() => expect((agent as unknown as { running: boolean }).running).toBe(true));
    await vi.waitFor(() => expect(resolveTurn).toBeTypeOf("function"));

    agent.switchSession("s_b");
    resolveTurn!();
    await sendPromise;

    const bMsgs = SessionStore.load("s_b");
    expect(hasText(bMsgs, "user", "A 继续问")).toBe(false);
    expect(hasText(bMsgs, "assistant", "旧轮回复")).toBe(false);
    expect(bMsgs.map((m) => m.content)).toEqual(["B 问"]);
  });
});

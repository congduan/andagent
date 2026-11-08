import { beforeEach, describe, expect, it, vi } from "vitest";
import { AgentEngine } from "./agent";
import { SessionStore } from "./sessions";
import { DEFAULT_SETTINGS } from "./bridge";
import type { AssistantMessage } from "@mariozechner/pi-ai";

// R88: 多模态输入——消息里存路径，发请求时补 base64。

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

type StreamCall = {
  messages: Array<{ role: string; content: unknown }>;
  model: { input: string[] };
};
let calls: StreamCall[] = [];

vi.mock("./pi", () => ({
  loadPi: async () => ({
    stream: (model: { input: string[] }, ctx: { messages: Array<{ role: string; content: unknown }> }) => {
      calls.push({ model, messages: ctx.messages });
      return {
        async *[Symbol.asyncIterator]() {
          yield { type: "text_delta", delta: "收到" };
        },
        result: async () => assistant("收到"),
      };
    },
  }),
}));

const settings = () => ({
  ...DEFAULT_SETTINGS,
  baseUrl: "http://127.0.0.1:8000/v1",
  apiKey: "test-key",
  model: "glm-4v-flash",
});

const IMAGE = {
  path: "chat-images/a.jpg",
  url: "/local/chat-images/a.jpg",
  width: 800,
  height: 600,
  bytes: 1_024,
  mimeType: "image/jpeg",
};

beforeEach(() => {
  localStorage.clear();
  calls = [];
  (window as unknown as { AndroidBridge?: unknown }).AndroidBridge = {
    chatImageBase64: (path: string) => (path === IMAGE.path ? "QUJD" : ""),
  };
});

describe("R88: 图片附件发送链路", () => {
  it("用户消息只存路径，发给模型时补成 base64 ImageContent", async () => {
    const agent = new AgentEngine(settings());
    await agent.send("这是什么", { images: [IMAGE] });

    const stored = SessionStore.load(agent.getSnapshot().sessionId) as Array<{ role: string; content: unknown }>;
    const userMessage = stored.find((message) => message.role === "user")!;
    expect(userMessage.content).toEqual([
      { type: "text", text: "这是什么" },
      { type: "image", path: IMAGE.path, mimeType: "image/jpeg" },
    ]);

    const sent = calls[0].messages.find((message) => message.role === "user")!;
    expect(sent.content).toEqual([
      { type: "text", text: "这是什么" },
      { type: "image", data: "QUJD", mimeType: "image/jpeg" },
    ]);
    // 视觉模型才声明 image 输入。
    expect(calls[0].model.input).toContain("image");
  });

  it("只发图不写字也能发送", async () => {
    const agent = new AgentEngine(settings());
    await agent.send("   ", { images: [IMAGE] });

    const stored = SessionStore.load(agent.getSnapshot().sessionId) as Array<{ role: string; content: unknown }>;
    const userMessage = stored.find((message) => message.role === "user")!;
    expect(userMessage.content).toEqual([
      { type: "image", path: IMAGE.path, mimeType: "image/jpeg" },
    ]);
    expect(calls).toHaveLength(1);
  });

  it("UI 快照里的用户消息渲染成 /local/ 图片 part", async () => {
    const agent = new AgentEngine(settings());
    await agent.send("看图", { images: [IMAGE] });
    const snapshot = agent.getSnapshot();
    const userMessage = snapshot.messages.find((message) => message.role === "user")!;
    const parts = userMessage.content as Array<{ type: string; image?: string }>;
    expect(parts).toHaveLength(2);
    expect(parts[1]).toEqual({ type: "image", image: "/local/chat-images/a.jpg" });
  });

  it("没有图也没有字时什么都不发", async () => {
    const agent = new AgentEngine(settings());
    await agent.send("   ", { images: [] });
    expect(calls).toHaveLength(0);
    expect(agent.getSnapshot().messages).toHaveLength(0);
  });

  it("非视觉模型不声明 image 输入", async () => {
    const agent = new AgentEngine({ ...settings(), model: "deepseek-chat" });
    await agent.send("纯文本");
    expect(calls[0].model.input).toEqual(["text"]);
  });
});

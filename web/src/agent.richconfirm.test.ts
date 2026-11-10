import { beforeEach, describe, expect, it, vi } from "vitest";
import { AgentEngine } from "./agent";
import { DEFAULT_SETTINGS } from "./bridge";
import { setLanguageMode } from "./i18n";

// R106: 路径 A —— Kotlin 参数闸返回 CONFIRM_REQUIRED 时的 a2ui 富决策确认。
// 用 fake pi 驱动真实 turn 循环，只替换询问出口与 execTool，不绕过引擎逻辑。

const state = vi.hoisted(() => ({
  appRichConfirm: vi.fn<(specText: string, argsText: string, argsLabel: string) => Promise<Record<string, unknown> | null>>(),
  appConfirm: vi.fn<(_: string) => Promise<boolean>>(),
  buildDecisionSpec: vi.fn<(_: unknown) => string | null>(),
  execTool: vi.fn<(...args: unknown[]) => Promise<{ ok: boolean; result: string }>>(),
  results: [] as unknown[],
}));

vi.mock("./ConfirmDialog", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./ConfirmDialog")>();
  return {
    ...actual,
    appConfirm: state.appConfirm,
    appRichConfirm: state.appRichConfirm,
    buildDecisionSpec: state.buildDecisionSpec,
  };
});

vi.mock("./bridge", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./bridge")>();
  return { ...actual, execTool: state.execTool };
});

vi.mock("./pi", () => ({
  loadPi: async () => ({
    stream: () => ({
      // 本轮无需流式事件；final 一律经 result() 返回。
      async *[Symbol.asyncIterator]() {},
      result: async () => state.results.shift(),
    }),
  }),
}));

const usage = {
  input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2,
  cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
};

const toolUseMessage = () => ({
  role: "assistant",
  content: [{ type: "toolCall", id: "t1", name: "file_write", arguments: { path: "/sdcard/x" } }],
  api: "openai-completions",
  provider: "custom",
  model: "test",
  usage,
  stopReason: "toolUse",
  timestamp: 1000,
}) as never;

const doneMessage = () => ({
  role: "assistant",
  content: [{ type: "text", text: "完成" }],
  api: "openai-completions",
  provider: "custom",
  model: "test",
  usage,
  stopReason: "stop",
  timestamp: 1001,
}) as never;

const testSettings = () => ({
  ...DEFAULT_SETTINGS,
  baseUrl: "http://127.0.0.1:8000/v1",
  apiKey: "test-key",
  model: "test-model",
});

const internalMessages = (agent: AgentEngine): unknown[] =>
  (agent as unknown as { messages: unknown[] }).messages;

beforeEach(() => {
  localStorage.clear();
  setLanguageMode("zh");
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
  state.appRichConfirm.mockReset().mockResolvedValue({ decision: "confirm" });
  state.appConfirm.mockReset().mockResolvedValue(true);
  state.buildDecisionSpec.mockReset().mockReturnValue('{"title":"t","fields":[]}');
  state.execTool
    .mockReset()
    .mockResolvedValueOnce({ ok: false, result: "CONFIRM_REQUIRED:删除文件 /sdcard/x" })
    .mockResolvedValue({ ok: true, result: "done" });
  state.results.length = 0;
  // 每轮 send：第 1 个 result 触发工具循环，第 2 个让 turn 收尾。
  state.results.push(toolUseMessage(), doneMessage());
});

describe("AgentEngine · R106 CONFIRM_REQUIRED 富决策确认", () => {
  it("decision=confirm → 同参数 + confirm=true 自动重试", async () => {
    const agent = new AgentEngine(testSettings());
    await agent.send("执行");

    expect(state.appRichConfirm).toHaveBeenCalledOnce();
    expect(state.execTool).toHaveBeenCalledTimes(2);
    const retryArgs = state.execTool.mock.calls[1][1] as Record<string, unknown>;
    expect(retryArgs.path).toBe("/sdcard/x");
    expect(retryArgs.confirm).toBe(true);
  });

  it("appRichConfirm 返回 null → 拒绝文案，不再重试", async () => {
    state.appRichConfirm.mockResolvedValue(null);
    const agent = new AgentEngine(testSettings());
    await agent.send("执行");

    expect(state.execTool).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(internalMessages(agent))).toContain("用户在确认弹窗中拒绝了本次操作");
  });

  it("spec 构造失败 → 回落纯文本 appConfirm（R106 fallback）", async () => {
    state.buildDecisionSpec.mockReturnValue(null);
    const agent = new AgentEngine(testSettings());
    await agent.send("执行");

    expect(state.appConfirm).toHaveBeenCalledWith(expect.stringContaining("危险操作确认"));
    expect(state.appRichConfirm).not.toHaveBeenCalled();
    // 文本确认默认允许 → 仍按原逻辑重试。
    expect(state.execTool).toHaveBeenCalledTimes(2);
  });

  it("构造的 spec 携带工具名、全参数与风险摘要", async () => {
    const agent = new AgentEngine(testSettings());
    await agent.send("执行");

    const parts = state.buildDecisionSpec.mock.calls[0][0] as {
      cardTitle: string;
      riskText: string;
      argsText: string;
      argsLabel: string;
    };
    expect(parts.cardTitle).toBe("危险操作确认");
    expect(parts.riskText).toContain("删除文件 /sdcard/x");
    // 必修项1: argsText 为纯 JSON 全文（无围栏前缀），由宿主自渲染，不受 clip。
    expect(parts.argsText.startsWith("```\n")).toBe(false);
    expect(parts.argsText).toContain('"path":"/sdcard/x"');
    expect(JSON.parse(parts.argsText)).toMatchObject({
      tool: "file_write",
      arguments: { path: "/sdcard/x" },
    });
    expect(parts.argsLabel).toBe("完整参数");
    // 完整参数全文也作为独立参数交给宿主（appRichConfirm 第 2 参）。
    expect(state.appRichConfirm.mock.calls[0][1]).toBe(parts.argsText);
  });

  // 必修项2: 路径 A 的 fallback 文案跟随语言，英文模式不再是中文「危险操作确认」。
  it("fallback 文案跟随语言：en 模式为英文 Dangerous action", async () => {
    setLanguageMode("en");
    state.buildDecisionSpec.mockReturnValue(null);
    const agent = new AgentEngine(testSettings());
    await agent.send("执行");

    expect(state.appConfirm).toHaveBeenCalledOnce();
    const message = state.appConfirm.mock.calls[0][0] as string;
    expect(message).toContain("Dangerous action");
    expect(message).not.toContain("危险操作确认");
  });
});

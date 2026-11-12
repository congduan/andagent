import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { execTool } from "./bridge";
import { setConfirmMode } from "./confirmMode";
import { setLanguageMode } from "./i18n";
import { TOOL_DESTRUCTIVE } from "./toolMeta";

const appConfirm = vi.hoisted(() => vi.fn<(_: string) => Promise<boolean>>());
const appRichConfirm = vi.hoisted(() =>
  vi.fn<(specText: string, argsText: string, argsLabel: string) => Promise<Record<string, unknown> | null>>(),
);
// R106: 保留真实 buildDecisionSpec / cancelConfirmDialog / 其余导出，
// 只替换文本与富确认两个询问出口（既有语义不变）。
vi.mock("./ConfirmDialog", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./ConfirmDialog")>();
  return { ...actual, appConfirm, appRichConfirm };
});

const toolExec = vi.fn((id: string) => {
  window.__andagent?.receive(JSON.stringify({ type: "tool.result", id, ok: true, result: "done" }));
});

beforeEach(() => {
  localStorage.clear();
  setLanguageMode("zh");
  window.AndroidBridge = { toolExec } as unknown as NonNullable<typeof window.AndroidBridge>;
  appConfirm.mockReset().mockResolvedValue(true);
  appRichConfirm.mockReset().mockResolvedValue({ decision: "confirm" });
  toolExec.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  delete window.AndroidBridge;
});

type RichSpec = {
  title: string;
  fields: Array<{
    type: string;
    key?: string;
    text?: string;
    buttons?: Array<{ value: string; label: string }>;
  }>;
};
const specOf = (call: number): RichSpec =>
  JSON.parse(appRichConfirm.mock.calls[call][0]) as RichSpec;

describe("operation confirmation gate", () => {
  it("defaults to off and calls the bridge directly", async () => {
    expect(await execTool("file_write", { path: "/off" })).toEqual({ ok: true, result: "done" });
    expect(appConfirm).not.toHaveBeenCalled();
    expect(appRichConfirm).not.toHaveBeenCalled();
    expect(toolExec).toHaveBeenCalledOnce();
  });

  it("asks only for marked tools in destructive mode and shows arguments", async () => {
    setConfirmMode("destructive");
    expect(await execTool("file_read", { path: "/read" })).toEqual({ ok: true, result: "done" });
    expect(appRichConfirm).not.toHaveBeenCalled();
    expect(await execTool("file_write", { path: "/write" })).toEqual({ ok: true, result: "done" });
    expect(appRichConfirm).toHaveBeenCalledOnce();
    // R106 必修项1: 完整参数作为独立 argsText 交给宿主，不再塞进 spec 的 TEXT_DISPLAY（会被 clip）。
    expect(appRichConfirm.mock.calls[0][1]).toContain('"path":"/write"');
    expect(appConfirm).not.toHaveBeenCalled();
    expect(toolExec).toHaveBeenCalledTimes(2);
  });

  it("asks for every native tool in all mode", async () => {
    setConfirmMode("all");
    await execTool("file_read", { path: "/all" });
    expect(appRichConfirm).toHaveBeenCalledOnce();
    expect(toolExec).toHaveBeenCalledOnce();
  });

  // R87c 必修1: Kotlin 侧 screen_lock_now destructive=true，web 端确认闸必须覆盖它；
  // display_mode 非破坏性，不应进 TOOL_DESTRUCTIVE。
  it("routes screen_lock_now through the destructive confirmation gate", async () => {
    expect(TOOL_DESTRUCTIVE.has("screen_lock_now")).toBe(true);
    expect(TOOL_DESTRUCTIVE.has("display_mode")).toBe(false);
    setConfirmMode("destructive");
    expect(await execTool("screen_lock_now", {})).toEqual({ ok: true, result: "done" });
    expect(appRichConfirm).toHaveBeenCalledOnce();
    expect(toolExec).toHaveBeenCalledOnce();
  });

  it("reuses a decision for identical calls within 3 seconds, then asks again", async () => {
    vi.useFakeTimers();
    setConfirmMode("destructive");
    await execTool("file_write", { path: "/repeat" });
    await execTool("file_write", { path: "/repeat" });
    expect(appRichConfirm).toHaveBeenCalledOnce();
    expect(toolExec).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3_001);
    await execTool("file_write", { path: "/repeat" });
    expect(appRichConfirm).toHaveBeenCalledTimes(2);
  });

  it("returns an explicit rejection and never calls the native bridge", async () => {
    setConfirmMode("destructive");
    appRichConfirm.mockResolvedValue(null);
    const result = await execTool("file_write", { path: "/reject" });
    expect(result).toEqual({ ok: false, result: "用户在确认弹窗中拒绝了本次操作" });
    expect(await execTool("file_write", { path: "/reject" })).toEqual(result);
    expect(appRichConfirm).toHaveBeenCalledOnce();
    expect(toolExec).not.toHaveBeenCalled();
  });

  // R106: destructive 闸确实走富决策卡（而非旧纯文本 appConfirm），且 spec 结构可用。
  it("routes the destructive gate through the rich decision card (CARD-only spec, host-rendered buttons)", async () => {
    setConfirmMode("destructive");
    await execTool("file_write", { path: "/rich" });
    expect(appRichConfirm).toHaveBeenCalledOnce();
    expect(appConfirm).not.toHaveBeenCalled();
    const spec = specOf(0);
    // R142 交互重构：spec 只含风险摘要 CARD；确认/取消按钮由宿主底部渲染。
    expect(spec.fields.some((f) => f.type === "CARD")).toBe(true);
    // 必修项1: spec 不含 TEXT_DISPLAY，完整参数改由宿主自渲染，绕开 1000 字 clip。
    expect(spec.fields.some((f) => f.type === "TEXT_DISPLAY")).toBe(false);
    // R142: BUTTON_GROUP 也移出 spec（单步宿主按钮取代「选中+提交」两步流）。
    expect(spec.fields.some((f) => f.type === "BUTTON_GROUP")).toBe(false);
  });

  // R106 必修项1 端到端：超长参数（>1000 字符 JSON）经确认闸完整到达宿主，无 "…" 截断。
  it("passes the full (>1000 char) argument JSON to the host without truncation", async () => {
    setConfirmMode("destructive");
    const args = { path: "/big", blob: "x".repeat(1500) };
    await execTool("file_write", args);
    expect(appRichConfirm).toHaveBeenCalledOnce();
    const argsText = appRichConfirm.mock.calls[0][1];
    expect(argsText.length).toBeGreaterThan(1000);
    expect(argsText).toBe(JSON.stringify(args)); // 全文，未 clip
    expect(argsText).not.toContain("…"); // 无省略号截断
    expect(argsText).toContain("x".repeat(1500));
  });
});

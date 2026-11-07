import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { execTool } from "./bridge";
import { setConfirmMode } from "./confirmMode";
import { setLanguageMode } from "./i18n";

const appConfirm = vi.hoisted(() => vi.fn<(_: string) => Promise<boolean>>());
vi.mock("./ConfirmDialog", () => ({ appConfirm }));

const toolExec = vi.fn((id: string) => {
  window.__andagent?.receive(JSON.stringify({ type: "tool.result", id, ok: true, result: "done" }));
});

beforeEach(() => {
  localStorage.clear();
  setLanguageMode("zh");
  window.AndroidBridge = { toolExec } as unknown as NonNullable<typeof window.AndroidBridge>;
  appConfirm.mockReset().mockResolvedValue(true);
  toolExec.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  delete window.AndroidBridge;
});

describe("operation confirmation gate", () => {
  it("defaults to off and calls the bridge directly", async () => {
    expect(await execTool("file_write", { path: "/off" })).toEqual({ ok: true, result: "done" });
    expect(appConfirm).not.toHaveBeenCalled();
    expect(toolExec).toHaveBeenCalledOnce();
  });

  it("asks only for marked tools in destructive mode and shows arguments", async () => {
    setConfirmMode("destructive");
    expect(await execTool("file_read", { path: "/read" })).toEqual({ ok: true, result: "done" });
    expect(appConfirm).not.toHaveBeenCalled();
    expect(await execTool("file_write", { path: "/write" })).toEqual({ ok: true, result: "done" });
    expect(appConfirm).toHaveBeenCalledWith(expect.stringContaining('"path":"/write"'));
    expect(toolExec).toHaveBeenCalledTimes(2);
  });

  it("asks for every native tool in all mode", async () => {
    setConfirmMode("all");
    await execTool("file_read", { path: "/all" });
    expect(appConfirm).toHaveBeenCalledOnce();
    expect(toolExec).toHaveBeenCalledOnce();
  });

  it("reuses a decision for identical calls within 3 seconds, then asks again", async () => {
    vi.useFakeTimers();
    setConfirmMode("destructive");
    await execTool("file_write", { path: "/repeat" });
    await execTool("file_write", { path: "/repeat" });
    expect(appConfirm).toHaveBeenCalledOnce();
    expect(toolExec).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3_001);
    await execTool("file_write", { path: "/repeat" });
    expect(appConfirm).toHaveBeenCalledTimes(2);
  });

  it("returns an explicit rejection and never calls the native bridge", async () => {
    setConfirmMode("destructive");
    appConfirm.mockResolvedValue(false);
    const result = await execTool("file_write", { path: "/reject" });
    expect(result).toEqual({ ok: false, result: "用户在确认弹窗中拒绝了本次操作" });
    expect(await execTool("file_write", { path: "/reject" })).toEqual(result);
    expect(appConfirm).toHaveBeenCalledOnce();
    expect(toolExec).not.toHaveBeenCalled();
  });
});

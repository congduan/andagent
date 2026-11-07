import { afterEach, describe, it, expect, vi } from "vitest";
import { cancelAgentWork, cancelToolByCallId, execTool } from "./bridge";

const STOPPED = "ERROR: 已被用户中断";

afterEach(() => {
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
});

// R39: cancel 钩子纯逻辑——无桥（浏览器预览/旧 APK）时必须静默无操作，
// 且未知 callId 不得触碰桥。
describe("cancelToolByCallId", () => {
  it("is a no-op without a bridge", () => {
    expect(() => cancelToolByCallId("call-1")).not.toThrow();
  });

  it("is a no-op for unknown callIds even with a bridge present", () => {
    let cancelled: string | undefined;
    (window as unknown as { AndroidBridge?: unknown }).AndroidBridge = {
      cancelTool: (id: string) => { cancelled = id; },
    };
    cancelToolByCallId("never-registered");
    expect(cancelled).toBeUndefined();
  });

  // R104: 卡片停止不能依赖原生回包，否则 future 已完成/回包丢失时卡片永远转圈。
  it("settles the in-flight tool and notifies the engine immediately", async () => {
    const toolExec = vi.fn();
    const cancelTool = vi.fn();
    window.AndroidBridge = { toolExec, cancelTool } as unknown as NonNullable<Window["AndroidBridge"]>;
    const seen: string[] = [];
    const listener = (event: Event) => seen.push(String((event as CustomEvent<string>).detail));
    window.addEventListener("andagent:tool-cancel", listener);
    try {
      const pending = execTool("shell_exec", { cmd: "sleep 60" }, "call-card");
      const reqId = toolExec.mock.calls[0][0] as string;
      cancelToolByCallId("call-card");
      await expect(pending).resolves.toEqual({ ok: false, result: STOPPED });
      expect(cancelTool).toHaveBeenCalledWith(reqId);
      expect(seen).toEqual(["call-card"]);
    } finally {
      window.removeEventListener("andagent:tool-cancel", listener);
    }
  });

  // 历史会话里的 toolCall 没有在跑的任务：仍然要通知引擎把卡片落成中断态。
  it("still notifies the engine for cards with no running task", () => {
    const seen: string[] = [];
    const listener = (event: Event) => seen.push(String((event as CustomEvent<string>).detail));
    window.addEventListener("andagent:tool-cancel", listener);
    try {
      cancelToolByCallId("orphan-call");
      expect(seen).toEqual(["orphan-call"]);
    } finally {
      window.removeEventListener("andagent:tool-cancel", listener);
    }
  });
});

// R104: 停止按钮必须能打断正在执行的工具，而不是等工具自己超时。
describe("stopping a running tool", () => {
  it("settles immediately when the signal is already aborted", async () => {
    const toolExec = vi.fn();
    window.AndroidBridge = { toolExec } as unknown as NonNullable<Window["AndroidBridge"]>;
    const controller = new AbortController();
    controller.abort();
    const result = await execTool("shell_exec", { cmd: "sleep 60" }, "call-1", undefined, controller.signal);
    expect(result.ok).toBe(false);
    expect(result.result).toBe(STOPPED);
    expect(toolExec).not.toHaveBeenCalled();
  });

  it("cancels the native call and resolves when aborted mid-flight", async () => {
    const toolExec = vi.fn();
    const cancelTool = vi.fn();
    window.AndroidBridge = { toolExec, cancelTool } as unknown as NonNullable<Window["AndroidBridge"]>;
    const controller = new AbortController();
    const pending = execTool("shell_exec", { cmd: "sleep 60" }, "call-2", undefined, controller.signal);
    expect(toolExec).toHaveBeenCalledOnce();
    const reqId = toolExec.mock.calls[0][0] as string;
    controller.abort();
    await expect(pending).resolves.toEqual({ ok: false, result: STOPPED });
    expect(cancelTool).toHaveBeenCalledWith(reqId);
  });

  it("cancelAgentWork resolves every in-flight tool without waiting for the native result", async () => {
    const toolExec = vi.fn();
    const cancelTool = vi.fn();
    window.AndroidBridge = { toolExec, cancelTool } as unknown as NonNullable<Window["AndroidBridge"]>;
    const pending = execTool("shell_exec", { cmd: "sleep 60" }, "call-3");
    cancelAgentWork();
    await expect(pending).resolves.toEqual({ ok: false, result: STOPPED });
    expect(cancelTool).toHaveBeenCalledTimes(1);
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { execTool } from "./bridge";

afterEach(() => {
  vi.useRealTimers();
  delete window.AndroidBridge;
});

describe("tool bridge timeout", () => {
  it("gives SSH transfers 30 minutes and keeps ordinary tools at 65 seconds", async () => {
    vi.useFakeTimers();
    const toolExec = vi.fn();
    window.AndroidBridge = { toolExec } as unknown as NonNullable<typeof window.AndroidBridge>;
    const download = execTool("ssh_download", {});
    const read = execTool("file_read", {});
    await vi.advanceTimersByTimeAsync(65_000);
    expect(await read).toEqual({ ok: false, result: "ERROR: 工具执行超时（60s）" });
    expect(toolExec).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1_735_000);
    expect(await download).toEqual({ ok: false, result: "ERROR: 长任务超时（30min）" });
  });
});

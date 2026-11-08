import { beforeEach, describe, expect, it, vi } from "vitest";
import { execTool, getAppVersion, getPermissionStatus, hasBridge, kvCall, requestPermissions } from "./bridge";
// 导入即安装假桥（import.meta.env.DEV 在 vitest 下为 true）。
import "./devMockBridge";

const FIXTURE_KEY = "andagent.dev.mock.tools";

beforeEach(() => {
  localStorage.clear();
});

describe("dev mock bridge", () => {
  it("installs a bridge and returns placeholder tool JSON", async () => {
    expect(hasBridge()).toBe(true);
    expect(getAppVersion()).toBe("dev-mock");

    vi.useFakeTimers();
    try {
      const pending = execTool("battery_detail", { level: 86 }, "call-1");
      await vi.advanceTimersByTimeAsync(300);
      const result = await pending;
      expect(result.ok).toBe(true);
      expect(JSON.parse(result.result)).toMatchObject({ mock: true, tool: "battery_detail", args: { level: 86 } });
    } finally {
      vi.useRealTimers();
    }
  });

  it("lets localStorage override a tool result, including failures", async () => {
    localStorage.setItem(FIXTURE_KEY, JSON.stringify({ shell_exec: "ERROR: boom" }));

    vi.useFakeTimers();
    try {
      const pending = execTool("shell_exec", { cmd: "ls" }, "call-2");
      await vi.advanceTimersByTimeAsync(300);
      await expect(pending).resolves.toEqual({ ok: false, result: "ERROR: boom" });
    } finally {
      vi.useRealTimers();
    }
  });

  it("auto-grants runtime permissions", async () => {
    const names = ["android.permission.CAMERA"];
    expect(getPermissionStatus(names)).toEqual({ "android.permission.CAMERA": "granted" });

    vi.useFakeTimers();
    try {
      const pending = requestPermissions(names);
      await vi.advanceTimersByTimeAsync(100);
      await expect(pending).resolves.toEqual({ "android.permission.CAMERA": true });
    } finally {
      vi.useRealTimers();
    }
  });

  it("backs the KV channel with localStorage (canvas/live and sessions)", () => {
    expect(kvCall("kv_set", { namespace: "demo", key: "n", value_json: "1" })).toEqual({
      ok: true,
      namespace: "demo",
      key: "n",
    });
    expect(kvCall("kv_get", { namespace: "demo", key: "n" })).toMatchObject({ found: true, value: 1 });
    expect(kvCall("kv_get", { namespace: "demo", key: "missing" })).toMatchObject({ found: false });
    expect(kvCall("kv_delete", { namespace: "demo", key: "n" })).toMatchObject({ deleted: true });
  });
});

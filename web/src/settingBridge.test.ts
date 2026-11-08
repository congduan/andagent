import { afterEach, describe, expect, it, vi } from "vitest";
import { kvSettingCall } from "./settingBridge";

type Bridge = NonNullable<Window["AndroidBridge"]>;

/** R96 review 必修项：设置通道主帧能力凭证（jsdom + mock AndroidBridge）。 */
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete window.AndroidBridge;
  delete (window as unknown as { __kvSettingToken?: unknown }).__kvSettingToken;
});

describe("R96 kvSettingCall 主帧凭证", () => {
  it("①无 token 等待 ~1200ms 超时返回 undefined，且不调用桥", async () => {
    vi.useFakeTimers();
    const fn = vi.fn(() => JSON.stringify({ found: false }));
    window.AndroidBridge = { kvSettingCall: fn } as unknown as Bridge;
    const pending = kvSettingCall("kv_get", { namespace: "secure", key: "engines" });
    await vi.advanceTimersByTimeAsync(1_300);
    await expect(pending).resolves.toBeUndefined();
    expect(fn).not.toHaveBeenCalled();
  });

  it("②token 就绪后携带 __token 调桥并解析结果", async () => {
    vi.useFakeTimers();
    const fn = vi.fn((_name: string, raw: string) =>
      JSON.stringify({ found: true, value: (JSON.parse(raw) as { __token?: string }).__token === "tok-96" ? [] : "bad" }),
    );
    window.AndroidBridge = { kvSettingCall: fn } as unknown as Bridge;
    (window as unknown as { __kvSettingToken?: string }).__kvSettingToken = "tok-96";
    const res = await kvSettingCall<{ found: boolean; value: unknown }>("kv_get", { namespace: "secure", key: "engines" });
    expect(fn).toHaveBeenCalledTimes(1);
    const [name, raw] = fn.mock.calls[0];
    expect(name).toBe("kv_get");
    expect(JSON.parse(raw)).toMatchObject({ namespace: "secure", key: "engines", __token: "tok-96" });
    expect(res).toEqual({ found: true, value: [] });
  });

  it("③桥返回 ERROR: 前缀返回 undefined", async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    const fn = vi.fn(() => "ERROR: 设置通道凭证无效");
    window.AndroidBridge = { kvSettingCall: fn } as unknown as Bridge;
    (window as unknown as { __kvSettingToken?: string }).__kvSettingToken = "tok-96";
    await expect(kvSettingCall("kv_get")).resolves.toBeUndefined();
    expect(fn).toHaveBeenCalledTimes(1);
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_SETTINGS, modelFetch } from "./bridge";
import { PROVIDERS, PROVIDER_GROUPS, dismissModelSetup, isModelConfigured, isValidModelUrl, listModels, needsModelSetup, normalizeModelUrl, providerForUrl, testModel } from "./modelConfig";

const settings = { ...DEFAULT_SETTINGS, baseUrl: " https://models.example/v1/chat/completions/ ", apiKey: " test-key ", model: "agent-model" };
afterEach(() => { localStorage.clear(); delete window.AndroidBridge; vi.unstubAllGlobals(); });

describe("model onboarding", () => {
  it("only opens automatically when unconfigured and not dismissed", () => {
    expect(needsModelSetup(DEFAULT_SETTINGS)).toBe(true);
    dismissModelSetup();
    expect(needsModelSetup(DEFAULT_SETTINGS)).toBe(false);
    localStorage.clear();
    expect(needsModelSetup(settings)).toBe(false);
    expect(isModelConfigured({ ...settings, model: " " })).toBe(false);
  });

  it("permits keyless local models and requires a key for known cloud presets", () => {
    expect(isModelConfigured({ ...settings, baseUrl: "http://192.168.1.10:11434/v1", apiKey: "" })).toBe(true);
    expect(isModelConfigured({ ...settings, baseUrl: "https://api.deepseek.com/v1", apiKey: "" })).toBe(false);
  });

  it("normalizes completion endpoints and rejects malformed addresses", () => {
    expect(normalizeModelUrl(settings.baseUrl)).toBe("https://models.example/v1");
    for (const url of ["", "file:///tmp/model", "https://u:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/#fragment"])
      expect(isValidModelUrl(url)).toBe(false);
  });
});

describe("provider presets", () => {
  it("buckets every preset once, keeps custom last, and stores base endpoints only", () => {
    const grouped = PROVIDER_GROUPS.flatMap(group => PROVIDERS.filter(p => p.group === group.id));
    expect(grouped).toHaveLength(PROVIDERS.length);
    expect(PROVIDERS[PROVIDERS.length - 1].id).toBe("custom");
    for (const preset of PROVIDERS) expect(preset.baseUrl).not.toMatch(/\/chat\/completions$/);
    expect(providerForUrl("https://ark.cn-beijing.volces.com/api/v3").id).toBe("ark");
    expect(providerForUrl("https://api.hunyuan.cloud.tencent.com/v1/chat/completions").id).toBe("hunyuan");
    expect(providerForUrl("https://my-gateway.example/v1").id).toBe("custom");
  });
});

describe("model connection", () => {
  it("fetches, validates and deduplicates the list using the draft endpoint", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: [{ id: "b" }, { id: "a" }, { id: "a" }, { id: 2 }, {}] })));
    vi.stubGlobal("fetch", fetcher);
    const signal = new AbortController().signal;
    expect(await listModels(settings, signal)).toEqual(["a", "b"]);
    expect(fetcher).toHaveBeenCalledWith("https://models.example/v1/models", expect.objectContaining({ method: "GET", signal, headers: expect.objectContaining({ Authorization: "Bearer test-key" }) }));
  });

  it("tests real tool calling and does not mistake plain text for tool support", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ function: { name: "ping" } }] } }] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ choices: [{ message: { content: "pong" } }] })));
    vi.stubGlobal("fetch", fetcher);
    expect(await testModel(settings, new AbortController().signal)).toBe(true);
    expect(await testModel(settings, new AbortController().signal)).toBe(false);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toMatchObject({ model: "agent-model", stream: false, tools: [{ function: { name: "ping" } }] });
  });

  it("omits auth for keyless servers and does not echo HTTP error bodies", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response("echoed secret", { status: 401, statusText: "Unauthorized" }));
    vi.stubGlobal("fetch", fetcher);
    await expect(listModels({ ...settings, apiKey: "" }, new AbortController().signal)).rejects.toThrow("HTTP 401 Unauthorized");
    expect(fetcher.mock.calls[0][1].headers).not.toHaveProperty("Authorization");
  });

  it("sends GET through the native bridge even before a model is configured", async () => {
    const start = vi.fn();
    window.AndroidBridge = { llmRequestStart: start, llmCancel: vi.fn() } as unknown as NonNullable<Window["AndroidBridge"]>;
    const controller = new AbortController();
    const response = modelFetch("http://lan:11434/v1/models", { method: "GET", signal: controller.signal });
    expect(start).toHaveBeenCalledWith(expect.any(String), "http://lan:11434/v1/models", "{}", "", "GET");
    controller.abort();
    await expect(response).rejects.toMatchObject({ name: "AbortError" });
    expect(window.AndroidBridge.llmCancel).toHaveBeenCalled();
  });
});

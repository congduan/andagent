import { modelFetch, type Settings } from "./bridge";

export interface ProviderPreset {
  id: string;
  name: string;
  baseUrl: string;
  requiresKey: boolean;
}

// OpenAI-compatible providers supported by the existing chat engine.
export const PROVIDERS: ProviderPreset[] = [
  { id: "deepseek", name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1", requiresKey: true },
  { id: "qwen", name: "通义千问 · Qwen", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", requiresKey: true },
  { id: "kimi", name: "Kimi", baseUrl: "https://api.moonshot.cn/v1", requiresKey: true },
  { id: "zhipu", name: "智谱 · GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4", requiresKey: true },
  { id: "siliconflow", name: "硅基流动 · SiliconFlow", baseUrl: "https://api.siliconflow.cn/v1", requiresKey: true },
  { id: "openrouter", name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", requiresKey: true },
  { id: "openai", name: "OpenAI", baseUrl: "https://api.openai.com/v1", requiresKey: true },
  { id: "ollama", name: "Ollama", baseUrl: "http://localhost:11434/v1", requiresKey: false },
  { id: "lmstudio", name: "LM Studio", baseUrl: "http://localhost:1234/v1", requiresKey: false },
  { id: "custom", name: "", baseUrl: "", requiresKey: false },
];

export function normalizeModelUrl(value: string): string {
  return value.trim().replace(/\/+$/, "").replace(/\/chat\/completions$/i, "");
}

export function isValidModelUrl(value: string): boolean {
  try {
    const url = new URL(normalizeModelUrl(value));
    return ["http:", "https:"].includes(url.protocol) && !!url.hostname && !url.username && !url.password && !url.search && !url.hash;
  } catch { return false; }
}

export function providerForUrl(value: string): ProviderPreset {
  const url = normalizeModelUrl(value);
  return PROVIDERS.find(p => p.baseUrl && normalizeModelUrl(p.baseUrl) === url) ?? PROVIDERS[PROVIDERS.length - 1];
}

export function isModelConfigured(settings: Settings): boolean {
  return isValidModelUrl(settings.baseUrl) && !!settings.model.trim() &&
    (!providerForUrl(settings.baseUrl).requiresKey || !!settings.apiKey.trim());
}

const ONBOARDING_KEY = "andagent.model-setup.dismissed.v1";
export function needsModelSetup(settings: Settings): boolean {
  if (isModelConfigured(settings)) return false;
  try { return localStorage.getItem(ONBOARDING_KEY) !== "1"; } catch { return true; }
}
export function dismissModelSetup(): void {
  try { localStorage.setItem(ONBOARDING_KEY, "1"); } catch { /* Banner remains available. */ }
}

async function request(settings: Settings, path: string, signal: AbortSignal, body?: unknown): Promise<unknown> {
  const response = await modelFetch(`${normalizeModelUrl(settings.baseUrl)}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type": "application/json",
      ...(settings.apiKey.trim() ? { Authorization: `Bearer ${settings.apiKey.trim()}` } : {}),
    },
    signal,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    // Do not display response bodies: some gateways echo credentials in errors.
    await response.body?.cancel();
    throw new Error(`HTTP ${response.status} ${response.statusText}`.trim());
  }
  return response.json();
}

export async function listModels(settings: Settings, signal: AbortSignal): Promise<string[]> {
  const result = await request(settings, "/models", signal) as { data?: Array<{ id?: unknown }> };
  return [...new Set((Array.isArray(result.data) ? result.data : [])
    .map(m => m?.id).filter((id): id is string => typeof id === "string" && !!id.trim()))].sort();
}

/** A real, non-streaming tool call; no device tools are executed. */
export async function testModel(settings: Settings, signal: AbortSignal): Promise<boolean> {
  const result = await request(settings, "/chat/completions", signal, {
    model: settings.model.trim(),
    stream: false,
    messages: [{ role: "user", content: "Connection test: call the ping tool once with no arguments." }],
    tools: [{ type: "function", function: {
      name: "ping", description: "Verify tool calling support.", parameters: { type: "object", properties: {}, required: [] },
    } }],
    tool_choice: "auto",
  }) as { choices?: Array<{ message?: { tool_calls?: Array<{ function?: { name?: string } }> } }> };
  return result.choices?.some(c => c.message?.tool_calls?.some(call => call.function?.name === "ping")) ?? false;
}

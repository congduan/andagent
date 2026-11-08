import { modelFetch, type Settings } from "./bridge";

/** Grouping mirrors how desktop clients (Cherry Studio / LobeChat) bucket providers. */
export type ProviderGroup = "cn" | "global" | "local";

export interface ProviderPreset {
  id: string;
  name: string;
  baseUrl: string;
  requiresKey: boolean;
  group: ProviderGroup;
  /** Short monogram for the picker tile; derived from the name when omitted. */
  mark?: string;
}

/**
 * OpenAI-compatible providers supported by the existing chat engine.
 * Keep `custom` last: `providerForUrl` falls back to the last entry.
 * Endpoints are the official OpenAI-compatible base URLs (no /chat/completions).
 */
export const PROVIDERS: ProviderPreset[] = [
  // 国内：直连即可，多数按 token 计费（含各家 token plan / 预付费包）
  { id: "deepseek", name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1", requiresKey: true, group: "cn", mark: "DS" },
  { id: "qwen", name: "通义千问", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", requiresKey: true, group: "cn", mark: "通" },
  { id: "hunyuan", name: "腾讯混元", baseUrl: "https://api.hunyuan.cloud.tencent.com/v1", requiresKey: true, group: "cn", mark: "混" },
  { id: "ark", name: "火山方舟", baseUrl: "https://ark.cn-beijing.volces.com/api/v3", requiresKey: true, group: "cn", mark: "火" },
  { id: "kimi", name: "Kimi", baseUrl: "https://api.moonshot.cn/v1", requiresKey: true, group: "cn", mark: "KI" },
  { id: "zhipu", name: "智谱 GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4", requiresKey: true, group: "cn", mark: "智" },
  { id: "qianfan", name: "百度千帆", baseUrl: "https://qianfan.baidubce.com/v2", requiresKey: true, group: "cn", mark: "千" },
  { id: "spark", name: "讯飞星火", baseUrl: "https://spark-api-open.xf-yun.com/v1", requiresKey: true, group: "cn", mark: "星" },
  { id: "stepfun", name: "阶跃星辰", baseUrl: "https://api.stepfun.com/v1", requiresKey: true, group: "cn", mark: "阶" },
  { id: "siliconflow", name: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", requiresKey: true, group: "cn", mark: "硅" },
  // 海外
  { id: "openai", name: "OpenAI", baseUrl: "https://api.openai.com/v1", requiresKey: true, group: "global", mark: "OA" },
  { id: "claude", name: "Claude", baseUrl: "https://api.anthropic.com/v1", requiresKey: true, group: "global", mark: "CL" },
  { id: "gemini", name: "Gemini", baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai", requiresKey: true, group: "global", mark: "GE" },
  { id: "grok", name: "Grok", baseUrl: "https://api.x.ai/v1", requiresKey: true, group: "global", mark: "X" },
  { id: "mistral", name: "Mistral", baseUrl: "https://api.mistral.ai/v1", requiresKey: true, group: "global", mark: "MI" },
  { id: "groq", name: "Groq", baseUrl: "https://api.groq.com/openai/v1", requiresKey: true, group: "global", mark: "GQ" },
  { id: "openrouter", name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", requiresKey: true, group: "global", mark: "OR" },
  // 本地 / 自建
  { id: "ollama", name: "Ollama", baseUrl: "http://localhost:11434/v1", requiresKey: false, group: "local", mark: "OL" },
  { id: "lmstudio", name: "LM Studio", baseUrl: "http://localhost:1234/v1", requiresKey: false, group: "local", mark: "LM" },
  { id: "custom", name: "", baseUrl: "", requiresKey: false, group: "local", mark: "+" },
];

/** Picker sections, in display order. */
export const PROVIDER_GROUPS: Array<{ id: ProviderGroup; label: "providerGroupCn" | "providerGroupGlobal" | "providerGroupLocal" }> = [
  { id: "cn", label: "providerGroupCn" },
  { id: "global", label: "providerGroupGlobal" },
  { id: "local", label: "providerGroupLocal" },
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

/**
 * R88: 视觉能力启发式。
 *
 * 设置里只有模型 ID（没有模型元数据表），所以用主流视觉模型的命名特征判断：
 * 命中即认为能看图（`Model.input` 会带上 "image"），未命中只给出提示、不阻断
 * 发送——自建/新模型名字可能不在名单里，交给服务端如实报错更可靠。
 */
const VISION_MODEL =
  /(gpt-4o|gpt-4\.1|gpt-4-turbo|gpt-5|o3|o4-mini|vision|pixtral|llava|internvl|minicpm-v|moondream|qvq|-vl\b|vl-|glm-4v|glm-4\.1v|glm-4\.5v|claude|gemini|grok-2v|grok-4|step-1v|yi-vl|doubao-.*vision)/i;

export function visionCapable(model: string): boolean {
  return VISION_MODEL.test(model.trim());
}

/**
 * R96: 设置页专用 KV 通道（secure/engines 搜索引擎配置读写）。
 *
 * 与 bridge.ts 的 kvCall（对 secure 全拒，服务 sessions 等普通命名空间 + canvas/live
 * iframe RPC 转发）严格分离：原生侧 AgentBridge.kvSettingCall 是唯一放行
 * secure/engines 的入口，且仅放行 kv_get/kv_set 的单键读写——canvas/live iframe
 * 的模型生成代码拿不到这个通道，搜索模板里的 API key 无法被对话内代码读出。
 *
 * R96 review 必修项：@JavascriptInterface 会注入所有 iframe，键名白名单挡不住
 * iframe 直调。native 每次页面加载生成随机主帧 token，仅经 evaluateJavascript
 * （只作用于主帧）注入 window.__kvSettingToken；本通道调用必须把它作为
 * args.__token 携带，native 校验不符即拒绝。iframe（sandbox opaque origin 无
 * parent 访问权；跨源同理）读不到主帧 window，故拿不到该凭证。
 *
 * 浏览器预览（无 AndroidBridge）返回 null/undefined，设置页据此禁用保存。
 */
export function hasSettingBridge(): boolean {
  return typeof (window as unknown as { AndroidBridge?: { kvSettingCall?: unknown } }).AndroidBridge?.kvSettingCall === "function";
}

/** R96 review 必修项：读取仅注入主帧 window 的能力凭证。 */
function readSettingToken(): string {
  const t = (window as unknown as { __kvSettingToken?: unknown }).__kvSettingToken;
  return typeof t === "string" && t ? t : "";
}

/** R96 review 必修项：token 可能晚于页面脚本注入，轮询等待最多 ~1200ms（100ms × 12）。
 *  R110-review 必修项①：导出为 waitForMainFrameToken，供资源中心通道复用（行为不变）。 */
export async function waitForMainFrameToken(): Promise<string | undefined> {
  for (let i = 0; i < 12; i++) {
    const token = readSettingToken();
    if (token) return token;
    await new Promise<void>((resolve) => { setTimeout(() => resolve(), 100); });
  }
  return readSettingToken() || undefined;
}

/** 调用设置页专用 KV 通道。失败（无桥/无凭证/ERROR/非 JSON）返回 undefined，不抛异常。 */
export async function kvSettingCall<T = unknown>(name: "kv_get" | "kv_set", args: Record<string, unknown> = {}): Promise<T | undefined> {
  const fn = (window as unknown as { AndroidBridge?: { kvSettingCall?: (name: string, argsJson: string) => string } }).AndroidBridge?.kvSettingCall;
  if (typeof fn !== "function") return undefined;
  const token = await waitForMainFrameToken();
  if (!token) return undefined; // R96 review 必修项：主帧凭证未注入，禁止发起调用
  let raw: string;
  try {
    // R96 review 必修项：token 放进 args JSON 内（__token），不改 AndroidBridge 调用形状。
    raw = fn(name, JSON.stringify({ ...args, __token: token }));
  } catch (e) {
    console.warn("kvSettingCall failed", e);
    return undefined;
  }
  if (typeof raw !== "string" || !raw) return undefined;
  if (raw.startsWith("ERROR:")) {
    console.warn(`kvSettingCall ${name} rejected: ${raw.slice(6).trim()}`);
    return undefined;
  }
  try {
    return JSON.parse(raw) as T;
  } catch {
    return undefined;
  }
}

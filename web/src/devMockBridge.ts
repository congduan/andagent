/**
 * 浏览器预览用的假 AndroidBridge（只在 `npm run dev` 下加载）。
 *
 * 真机环境 = WebView + Kotlin 注入的 `AndroidBridge` + 原生向
 * `window.__andagent.receive` 回推的事件。PC 上预览时没有任何原生层，
 * 本模块用同步函数 + 定时回推事件把这套协议补上，使 `npm run dev` 可以跑通：
 *   - 设置读写（沿用无桥预览的 localStorage 键 `andagent.settings.dev.v1`）
 *   - 工具调用（返回占位 JSON，可用 localStorage 覆盖成任意 fixture）
 *   - LLM 流式请求（浏览器直连，需服务端放行 CORS；回推 llm.headers/chunk/done）
 *   - 权限、主题、语言、UI 命令、KV、导出
 *
 * 刻意不实现的部分（设置页会显示「仅能在 App 内使用」，与无桥预览一致）：
 * `kvSettingCall`（secure/engines 主帧凭证通道）、`resource_hub_call`、
 * `externalState*`、`sshCredential*`。真实能力请用 chrome://inspect 调真机验证。
 *
 * 关闭：启动时设 `VITE_ANDAGENT_MOCK_BRIDGE=0`，即回到「无桥」预览。
 * 覆盖工具返回：localStorage["andagent.dev.mock.tools"] =
 *   { "shell_exec": "{\"exitCode\":0,\"stdout\":\"ok\"}",
 *     "battery_detail": { "ok": false, "result": "ERROR: 演示失败" } }
 * 控制台调试：window.__andagentDev.push({ type: "notice", message: "hi" })
 */

const SETTINGS_KEY = "andagent.settings.dev.v1";
const FIXTURE_KEY = "andagent.dev.mock.tools";
const KV_PREFIX = "andagent.dev.kv.";
const TOOL_LATENCY_MS = 250;

type MockBridge = NonNullable<Window["AndroidBridge"]>;
type Fixture = string | { ok?: boolean; result?: string };
type ToolResult = { ok: boolean; result: string };

function safeParse<T>(raw: string | null | undefined): T | undefined {
  if (!raw) return undefined;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return undefined;
  }
}

/** 事件回推：与 Kotlin 同一条通道。 */
function push(event: Record<string, unknown>): void {
  window.__andagent?.receive(JSON.stringify(event));
}

/** vite HMR 重新执行本模块时 window.fetch 可能已被代理，缓存首次拿到的原生 fetch。 */
const host = window as typeof window & {
  __andagentDevNativeFetch?: typeof fetch;
  __andagentDev?: { push: typeof push; fixtures: () => Record<string, Fixture> };
};
const nativeFetch: typeof fetch =
  host.__andagentDevNativeFetch ?? (host.__andagentDevNativeFetch = window.fetch.bind(window));

/* ------------------------------- 工具 ------------------------------- */

function fixtures(): Record<string, Fixture> {
  const parsed = safeParse<Record<string, Fixture>>(localStorage.getItem(FIXTURE_KEY));
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
}

function resolveTool(name: string, args: unknown): ToolResult {
  const fixture = fixtures()[name];
  if (typeof fixture === "string") return { ok: !fixture.startsWith("ERROR:"), result: fixture };
  if (fixture && typeof fixture === "object" && typeof fixture.result === "string") {
    return { ok: fixture.ok !== false, result: fixture.result };
  }
  return {
    ok: true,
    result: JSON.stringify({
      mock: true,
      tool: name,
      args,
      note: `浏览器预览的占位结果：在 localStorage["${FIXTURE_KEY}"] 里配置 ${name} 的真实返回。`,
    }),
  };
}

const toolTimers = new Map<string, number>();

/* -------------------------------- LLM -------------------------------- */

const llmInflight = new Map<string, AbortController>();

function startLlm(id: string, url: string, headersJson: string, body: string, method: string): void {
  const controller = new AbortController();
  llmInflight.set(id, controller);
  const upper = method.toUpperCase();
  void (async () => {
    try {
      const headers = safeParse<Record<string, string>>(headersJson) ?? {};
      const response = await nativeFetch(url, {
        method: upper,
        headers,
        body: upper === "GET" || upper === "HEAD" ? undefined : body,
        signal: controller.signal,
      });
      push({
        type: "llm.headers",
        id,
        status: response.status,
        statusText: response.statusText,
        headers: Object.fromEntries(response.headers.entries()),
      });
      const reader = response.body?.getReader();
      if (!reader) {
        push({ type: "llm.done", id });
        return;
      }
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        if (lines.length > 0) push({ type: "llm.chunks", id, lines });
      }
      buffer += decoder.decode();
      if (buffer) push({ type: "llm.chunk", id, line: buffer });
      push({ type: "llm.done", id });
    } catch (error) {
      if (controller.signal.aborted) return;
      const message = error instanceof Error ? error.message : String(error);
      push({
        type: "llm.error",
        id,
        message: /failed to fetch|networkerror|load failed/i.test(message)
          ? `${message}（浏览器预览：确认该端点的 CORS 允许 http://localhost:5173）`
          : message,
      });
    } finally {
      llmInflight.delete(id);
    }
  })();
}

/* -------------------------------- KV -------------------------------- */

function kvCall(name: string, argsJson: string): string {
  const args = safeParse<Record<string, unknown>>(argsJson) ?? {};
  const ns = typeof args.namespace === "string" && args.namespace ? args.namespace : "default";
  const key = typeof args.key === "string" ? args.key : "";
  const storageKey = `${KV_PREFIX}${ns}/${key}`;
  const nsPrefix = `${KV_PREFIX}${ns}/`;
  switch (name) {
    case "kv_get": {
      const raw = localStorage.getItem(storageKey);
      if (raw === null) return JSON.stringify({ found: false, namespace: ns, key });
      const value = safeParse<unknown>(raw);
      if (value === undefined) return JSON.stringify({ found: false, namespace: ns, key });
      return JSON.stringify({ found: true, namespace: ns, key, value });
    }
    case "kv_set": {
      const raw = typeof args.value_json === "string" ? args.value_json : JSON.stringify(args.value ?? null);
      localStorage.setItem(storageKey, raw);
      return JSON.stringify({ ok: true, namespace: ns, key });
    }
    case "kv_delete": {
      if (localStorage.getItem(storageKey) === null) return `ERROR: 键不存在：${ns}:${key}`;
      localStorage.removeItem(storageKey);
      return JSON.stringify({ deleted: true, namespace: ns, key });
    }
    case "kv_keys": {
      const prefix = typeof args.prefix === "string" ? args.prefix : "";
      const keys = Object.keys(localStorage)
        .filter((k) => k.startsWith(nsPrefix + prefix))
        .map((k) => ({ key: k.slice(nsPrefix.length), ttl: -1 }));
      return JSON.stringify({ namespace: ns, keys });
    }
    case "kv_clear": {
      for (const k of Object.keys(localStorage)) if (k.startsWith(nsPrefix)) localStorage.removeItem(k);
      return JSON.stringify({ cleared: true, namespace: ns });
    }
    default:
      return `ERROR: 未知 KV 工具 ${name}`;
  }
}

/* ------------------------------ 安装 ------------------------------ */

function ack(action: string): string {
  return JSON.stringify({ ok: true, action });
}

function unavailable(what: string): string {
  return `ERROR: 浏览器预览不支持${what}（请在 App 内验证）`;
}

function names(raw: string): string[] {
  const parsed = safeParse<string[]>(raw);
  return Array.isArray(parsed) ? parsed.filter((n): n is string => typeof n === "string") : [];
}

function orientation(): string {
  return window.matchMedia("(orientation: landscape)").matches ? "landscape-primary" : "portrait-primary";
}

function install(): void {
  const mock: MockBridge = {
    /* 设置：沿用无桥预览的 localStorage，来回切换两种预览模式不丢配置。 */
    getSettings: () => localStorage.getItem(SETTINGS_KEY) ?? "{}",
    saveSettings: (json) => {
      try {
        localStorage.setItem(SETTINGS_KEY, json);
      } catch {
        /* 配额满：忽略，与预览模式行为一致 */
      }
    },
    getAppVersion: () => JSON.stringify({ version: "dev-mock", commit: "dev" }),
    setUiLanguage: (mode) => {
      try {
        localStorage.setItem("andagent.dev.uiLanguage", mode);
      } catch {
        /* ignore */
      }
    },

    /* 主题 */
    systemTheme: () => (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"),
    reportTheme: () => {},

    /* 工具：占位结果 + 可覆盖 fixture */
    toolExec: (id, name, argsJson) => {
      const args = safeParse<unknown>(argsJson) ?? {};
      console.info(`[dev-bridge] toolExec ${name}`, args);
      const timer = window.setTimeout(() => {
        toolTimers.delete(id);
        const { ok, result } = resolveTool(name, args);
        push({ type: "tool.result", id, ok, result });
      }, TOOL_LATENCY_MS);
      toolTimers.set(id, timer);
    },
    cancelTool: (reqId) => {
      const timer = toolTimers.get(reqId);
      if (timer !== undefined) window.clearTimeout(timer);
      toolTimers.delete(reqId);
    },

    /* LLM 代理：浏览器直连，按 SSE 行回推 */
    llmRequestStart: (id, url, headersJson, body, method) => startLlm(id, url, headersJson, body, method),
    llmFetchStart: (id, url, headersJson, body) => startLlm(id, url, headersJson, body, "POST"),
    llmCancel: (id) => {
      llmInflight.get(id)?.abort();
      llmInflight.delete(id);
    },

    /* 权限：一律 granted，避免 dev 下反复弹确认 */
    requestPermissions: (namesJson) => {
      const list = names(namesJson);
      window.setTimeout(() => {
        push({ type: "permission.result", granted: Object.fromEntries(list.map((n) => [n, true])) });
      }, 60);
    },
    permissionStatus: (namesJson) =>
      JSON.stringify(Object.fromEntries(names(namesJson).map((n) => [n, "granted"]))),
    openAppSettings: () => {},

    /* KV（canvas / live 围栏的 andagent.kv、sessions 的持久层） */
    kvCall,

    /* R150: 卡片朗读——浏览器预览无系统 TTS，返回与真机同形状的占位串，不炸。 */
    kvTtsSpeak: (_text) => "OK: speaking(mock)",
    kvTtsStop: () => "OK: stopped",
    kvTtsState: () => JSON.stringify({ available: true, speaking: false }),

    /* 深链 / a2ui：空收件箱 */
    consumeDeeplink: () => "[]",
    consumeA2uiForms: () => "[]",

    /* 原生专有界面：诚实报错，避免假装成功 */
    openEmbeddedWebview: () => unavailable("内嵌网页"),
    dbGuiOpen: () => unavailable("数据库浏览器"),
    exportConversation: (title, markdown) => {
      try {
        const blob = new Blob([markdown], { type: "text/markdown;charset=utf-8" });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = `${title || "conversation"}.md`;
        anchor.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        return `浏览器预览：已下载 ${anchor.download}`;
      } catch (e) {
        return `ERROR: 导出失败：${String(e)}`;
      }
    },

    /* 记忆：只读空列表，写入静默忽略 */
    memoryPrompt: () => "",
    memoryCall: (name) => (name === "memory_get_all" ? "[]" : "{}"),

    /* 技能 + 自我扩展（my_tool_* 元工具 schema 样例，自建工具列表为空） */
    extensionTools: () => JSON.stringify([
      { name: "my_tool_create", description: "[mock] 创建并注册新的自建工具（自我扩展）。", parameters: { type: "object", properties: { name: { type: "string" }, description: { type: "string" }, code: { type: "string" }, allow: { type: "string" }, parameters_json: { type: "string" }, reason: { type: "string" } }, required: ["name", "description", "code"] } },
      { name: "my_tool_list", description: "[mock] 列出全部自建工具（my_*）。", parameters: { type: "object", properties: {} } },
      { name: "my_tool_read", description: "[mock] 读取自建工具的 JS 源码。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
      { name: "my_tool_update", description: "[mock] 修改自建工具，需 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, code: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "confirm"] } },
      { name: "my_tool_delete", description: "[mock] 删除自建工具，需 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "confirm"] } },
      { name: "my_tool_test", description: "[mock] 用测试参数运行自建工具。", parameters: { type: "object", properties: { name: { type: "string" }, args_json: { type: "string" } }, required: ["name"] } },
      { name: "my_tool_enable", description: "[mock] 启用或停用自建工具。", parameters: { type: "object", properties: { name: { type: "string" }, enabled: { type: "boolean" } }, required: ["name", "enabled"] } },
      { name: "my_tool_share", description: "[mock] 导出自建工具为技能 ZIP。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
    ]),
    skillPrompt: () => "",
    skillManage: () => unavailable("技能管理"),
    mcpManage: () => unavailable("MCP 管理"),

    /* 小组件 / 定时任务：列表为空，操作报错 */
    widgetList: () => "[]",
    widgetSave: () => unavailable("桌面小组件"),
    widgetPin: () => unavailable("桌面小组件"),
    widgetRefresh: () => unavailable("桌面小组件"),
    widgetDelete: () => unavailable("桌面小组件"),
    cronList: () => "[]",
    cronToggle: () => unavailable("定时任务"),
    cronDelete: () => unavailable("定时任务"),
    cronPauseAll: () => unavailable("定时任务"),
    cronExactAllowed: () => false,
    cronOpenExactSettings: () => unavailable("精确闹钟设置"),

    /* Termux / 终端 */
    termuxStatus: () => "浏览器预览：Termux 仅在 App 内可用",
    termuxGrantPermission: () => unavailable("Termux 授权需在电脑执行 ADB 命令"),
    termuxTerminalExec: () => unavailable("内嵌终端"),
    termuxTerminalWrite: () => unavailable("内嵌终端"),
    termuxTerminalPoll: () => "",

    /* DevTools / 健康 / 投屏 */
    devtoolsStatus: () =>
      JSON.stringify({ enabled: false, debugBuild: true, pages: [], error: "浏览器预览：DevTools 仅能在 App 内使用" }),
    devtoolsSetEnabled: () => unavailable("WebView 远程调试"),
    healthPermissionRequest: () =>
      JSON.stringify({ requested: false, message: "浏览器预览：健康数据仅能在 App 内使用" }),
    projectionStatus: () =>
      JSON.stringify({ channel: "none", ready: false, recording: false, error: "浏览器预览：屏幕捕获仅能在 App 内使用" }),
    projectionRequest: () => JSON.stringify({ requested: false, message: "浏览器预览：屏幕捕获仅能在 App 内使用" }),
    projectionStop: () => unavailable("屏幕捕获"),

    /* 语音输入：不伪造转写结果，直接报错 */
    saveAudioBase64: () => JSON.stringify({ ok: false, message: "浏览器预览不支持语音输入" }),
    transcribeAudio: (id) => {
      window.setTimeout(() => {
        push({ type: "tool.result", id, ok: false, result: "ERROR: 浏览器预览不支持语音转写" });
      }, 100);
    },

    /* UI 八域：ack 真实结构 + 真实视口尺寸 */
    uiToast: (message) => {
      console.info(`[dev-bridge] toast: ${message}`);
      return ack("toast");
    },
    uiHaptic: () => ack("haptic"),
    uiKeepScreenOn: () => ack("keepScreenOn"),
    uiImmersive: () => ack("immersive"),
    uiOrientation: () => ack("orientation"),
    navBack: () => ack("back"),
    navMinimize: () => ack("minimize"),
    uiShare: (text) => {
      void navigator.clipboard?.writeText(text).catch(() => {});
      return ack("share");
    },
    uiSecureFlag: () => ack("secureFlag"),
    uiKeyboard: () => ack("keyboard"),
    uiStatus: () =>
      JSON.stringify({
        ok: true,
        activityAlive: true,
        keyboardVisible: false,
        immersive: false,
        keepScreenOn: false,
        secure: false,
        orientation: orientation(),
      }),
    uiScreenMetrics: () => {
      const density = window.devicePixelRatio || 1;
      return JSON.stringify({
        ok: true,
        width: window.innerWidth,
        height: window.innerHeight,
        widthDp: Math.round(window.innerWidth / density),
        heightDp: Math.round(window.innerHeight / density),
        density,
        densityDpi: Math.round(density * 160),
        orientation: orientation(),
      });
    },
    webReload: () => {
      window.setTimeout(() => window.location.reload(), 0);
      return ack("reload");
    },
  };

  window.AndroidBridge = mock;
  host.__andagentDev = { push, fixtures };
  console.info(
    "[dev-bridge] 浏览器预览已注入假 AndroidBridge：工具返回占位 JSON，" +
      `可在 localStorage["${FIXTURE_KEY}"] 覆盖；LLM 由浏览器直连（需 CORS）。` +
      "设 VITE_ANDAGENT_MOCK_BRIDGE=0 可关闭。",
  );
}

if (import.meta.env.DEV) install();

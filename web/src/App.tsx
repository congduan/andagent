import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import {
  AssistantRuntimeProvider,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  useAui,
  useAuiState,
  useExternalStoreRuntime,
  type TextMessagePartProps,
  type ThreadMessageLike,
} from "@assistant-ui/react";
import { AgentEngine } from "./agent";
import { createBackLayerManager } from "./backLayers";
import {
  consumeDeeplink,
  getAppVersion,
  exportConversation,
  hasBridge,
  installLLMFetch,
  loadSettings,
  saveAudioBlob,
  transcribeAudio,
  type DeeplinkDetail,
  type Settings,
} from "./bridge";
import { CopyMenu, selectedText, useLongPress } from "./CopyMenu";
import { Drawer } from "./Drawer";
import { ConfirmDialog } from "./ConfirmDialog";
import { conversationMarkdown } from "./exportConversation";
import { MarkdownText } from "./Markdown";
import { MessageNav, type MessageNavItem } from "./MessageNav";
import { SettingsPanel, type SettingsPage } from "./Settings";
import { ModelSetup } from "./ModelSetup";
import { dismissModelSetup, isModelConfigured, needsModelSetup } from "./modelConfig";
import { TEMPLATE_GROUPS } from "./templates";
import { ToolCard } from "./ToolCard";
import { fmtTokens } from "./usage";
import { useI18n } from "./i18n";
import { showError, showSuccess } from "./toast";
import { usePresence } from "./usePresence";

/** metadata.custom 透传的每条消息消耗（agent.ts toUiMessages 写入）。 */
interface MessageUsageMeta {
  usage?: { input?: number; output?: number };
  durationMs?: number;
  aborted?: boolean;
}

function useMessageUsageMeta(): MessageUsageMeta | null {
  return useAuiState(
    (state) => (state.message.metadata?.custom as MessageUsageMeta | undefined) ?? null,
  );
}

/** 消息底部 meta 行：↑in ↓out · 耗时；中断消息如实标注。 */
function MessageMetaLine() {
  const { t } = useI18n();
  const running = useAuiState((state) => state.message.status?.type === "running");
  const meta = useMessageUsageMeta();
  if (running || !meta) return null;
  if (meta.aborted) return <div className="msg-meta">{t("aborted")}</div>;
  const input = meta.usage?.input ?? 0;
  const output = meta.usage?.output ?? 0;
  if (input <= 0 && output <= 0) return null;
  return (
    <div className="msg-meta">
      ↑{fmtTokens(input)} ↓{fmtTokens(output)}
      {meta.durationMs !== undefined && <> · {(meta.durationMs / 1000).toFixed(1)}s</>}
    </div>
  );
}

function useMessageText(): string {
  const { t: translate } = useI18n();
  return useAuiState((state) =>
    state.message.content
      .map((part) => {
        if (part.type === "text") return part.text;
        // Include reasoning so 长按复制 covers 思考过程 too.
        if (part.type === "reasoning") {
          const t = (part as { text?: string }).text ?? "";
          return t ? `【${translate("reasoningCopy")}】\n${t}` : "";
        }
        return "";
      })
      .filter(Boolean)
      .join("\n")
      .trim(),
  );
}

/** Full transcript of an assistant message: thinking + text + tool calls/results. */
function useFullCopyText(): string {
  const { t: translate } = useI18n();
  return useAuiState((state) =>
    state.message.content
      .map((part) => {
        if (part.type === "text") return part.text;
        if (part.type === "reasoning") {
          const t = (part as { text?: string }).text ?? "";
          return t ? `【${translate("reasoningCopy")}】\n${t}` : "";
        }
        if (part.type === "tool-call") {
          const tc = part as {
            toolName?: string;
            args?: unknown;
            argsText?: string;
            result?: string | unknown;
            isError?: boolean;
          };
          const lines = [`【${translate("toolCopy")}】${tc.toolName ?? "?"}`];
          if (tc.argsText) lines.push(`${translate("arguments")}: ${tc.argsText}`);
          if (tc.result !== undefined && tc.result !== null) {
            const r =
              typeof tc.result === "string" ? tc.result : JSON.stringify(tc.result, null, 1);
            lines.push(`${translate("result")}: ${r}`);
          }
          if (tc.isError) lines.push(`(${translate("errorCopy")})`);
          return lines.join("\n");
        }
        return "";
      })
      .filter(Boolean)
      .join("\n\n")
      .trim(),
  );
}

function UserText({ text }: TextMessagePartProps) {
  return <div className="user-text">{text}</div>;
}

function UserMessage() {
  const text = useMessageText();
  const messageId = useAuiState((state) => state.message.id);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPress = useLongPress((x, y) => setMenu({ x, y }));
  return (
    // id 供「对话记录」快捷导航定位（MessageNav 用 msg-<id> 查找节点）。
    <MessagePrimitive.Root id={`msg-${messageId}`} className="msg msg-user" {...longPress}>
      <div className="user-bubble">
        <MessagePrimitive.Parts components={{ Text: UserText }} />
      </div>
      {menu && text && (
        <CopyMenu text={text} x={menu.x} y={menu.y} onClose={() => setMenu(null)} />
      )}
    </MessagePrimitive.Root>
  );
}

function AssistantReasoning(props: {
  text: string;
  status?: { type: string } | null;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const running = props.status?.type === "running";
  // 流式思考内容自动滚底：仅当用户停在底部附近时跟随（手动上滚阅读历史不打断）。
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);
  useEffect(() => {
    const el = bodyRef.current;
    if (!el || !running) return;
    if (stickRef.current) el.scrollTop = el.scrollHeight;
  }, [props.text, running]);
  return (
    <div className={`thinking ${open || running ? "open" : ""}`}>
      <button
        type="button"
        className="thinking-toggle"
        onClick={() => {
          // 划选标题文字时不要误触折叠。
          if (selectedText()) return;
          setOpen((v) => !v);
        }}
        aria-expanded={open || running}
      >
        <span className={`thinking-caret${open || running ? " open" : ""}`} aria-hidden="true">
          ▸
        </span>
        {running ? t("thinkingNow") : t("thinking")}
      </button>
      {/* 常驻挂载 + 高度过渡：折叠/展开不再瞬间跳变。 */}
      <div className={`thinking-body-wrap${open || running ? " open" : ""}`} aria-hidden={!(open || running)}>
        <div className="thinking-body-inner">
          <div
            className="thinking-body"
            ref={bodyRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
            }}
          >{props.text}</div>
          <button
            type="button"
            className="thinking-copy"
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(props.text);
                setCopied(true);
                window.setTimeout(() => setCopied(false), 1400);
              } catch {
                /* clipboard unavailable */
              }
            }}
          >
            {copied ? t("copied") : t("copy")}
          </button>
        </div>
      </div>
    </div>
  );
}

/** 是否已收到可见内容（文本 / 思考 / 工具调用）；都没有说明还在等首包。 */
function useHasAssistantContent(): boolean {
  return useAuiState((state) =>
    state.message.content.some((part) => {
      if (part.type === "text") return part.text.trim().length > 0;
      if (part.type === "reasoning") {
        return ((part as { text?: string }).text ?? "").trim().length > 0;
      }
      if (part.type === "tool-call") return true;
      return false;
    }),
  );
}

function AssistantMessage() {
  const { t } = useI18n();
  const running = useAuiState((state) => state.message.status?.type === "running");
  const hasContent = useHasAssistantContent();
  const text = useFullCopyText();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPress = useLongPress((x, y) => setMenu({ x, y }));
  return (
    <MessagePrimitive.Root className="msg msg-assistant" {...longPress}>
      <div className="assistant-body">
        <MessagePrimitive.Parts
          components={{
            Text: MarkdownText,
            Reasoning: AssistantReasoning,
            tools: { Fallback: ToolCard },
          }}
        />
        {running &&
          (hasContent ? (
            <span className="stream-cursor" aria-hidden="true" />
          ) : (
            <span className="stream-spinner" role="status" aria-label={t("thinkingNow")} />
          ))}
        <MessageMetaLine />
        <MessagePrimitive.Error>
          <div className="msg-error">{t("renderFailed")}</div>
        </MessagePrimitive.Error>
      </div>
      {menu && text && (
        <CopyMenu text={text} x={menu.x} y={menu.y} onClose={() => setMenu(null)} />
      )}
    </MessagePrimitive.Root>
  );
}

function TemplateGrid() {
  const { language } = useI18n();
  const aui = useAui();
  return (
    <div className="template-groups">
      {TEMPLATE_GROUPS.map((group) => (
        <div className="template-group" key={group.label.zh}>
          <div className="template-group-label">{group.label[language]}</div>
          <div className="template-grid">
            {group.templates.map((t) => (
              <button
                type="button"
                className="template-card"
                key={t.title.zh}
                title={t.prompt[language]}
                onClick={() =>
                  aui.thread.append({
                    role: "user",
                    content: [{ type: "text", text: t.prompt[language] }],
                  })
                }
              >
                <span className="template-icon" aria-hidden="true">
                  {t.icon}
                </span>
                <span className="template-title">{t.title[language]}</span>
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function Composer({ hasKey }: { hasKey: boolean }) {
  const { t } = useI18n();
  const aui = useAui();
  const running = useAuiState((state) => state.thread.isRunning);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const [voiceState, setVoiceState] = useState<"idle" | "recording" | "transcribing">("idle");
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const startTimeRef = useRef(0);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      chunksRef.current = [];
      const mr = new MediaRecorder(stream);
      mr.ondataavailable = (e) => chunksRef.current.push(e.data);
      mr.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        const seconds = Math.round((Date.now() - startTimeRef.current) / 1000);
        const blob = new Blob(chunksRef.current, { type: mr.mimeType || "audio/webm" });
        if (blob.size < 800) {
          setVoiceState("idle");
          showError(t("voiceTooShort"));
          return;
        }
        setVoiceState("transcribing");
        // WebView MediaRecorder emits audio/webm (opus) which most
        // /audio/transcriptions endpoints reject — decode to 16kHz mono WAV
        // locally, save through the bridge, then transcribe.
        try {
          const wavPath = await saveAudioBlob(blob);
          const res = await transcribeAudio(wavPath, "zh");
          if (res.ok && inputRef.current) {
            const ta = inputRef.current;
            const existing = ta.value ? ta.value + " " : "";
            // assistant-ui composer is controlled by its own store; set via native setter
            const setter = Object.getOwnPropertyDescriptor(
              window.HTMLTextAreaElement.prototype,
              "value",
            )?.set;
            setter?.call(ta, existing + res.result.trim());
            ta.dispatchEvent(new Event("input", { bubbles: true }));
            setVoiceState("idle");
          } else {
            setVoiceState("idle");
            showError(res.result);
          }
        } catch (e) {
          setVoiceState("idle");
          showError(`${t("transcribeFailed")}：${String(e)}`);
        }
      };
      mr.start();
      recorderRef.current = mr;
      startTimeRef.current = Date.now();
      setVoiceState("recording");
      // Auto-stop at 60s to protect memory.
      window.setTimeout(() => {
        if (voiceStateRef.current === "recording") stopRecording();
      }, 60_000);
    } catch (e) {
      showError(`${t("micFailed")}：${String(e)} (RECORD_AUDIO)`);
    }
  };

  const stopRecording = () => {
    recorderRef.current?.stop();
    recorderRef.current = null;
    if (voiceStateRef.current === "recording") setVoiceState("transcribing");
  };

  const voiceStateRef = useRef(voiceState);
  voiceStateRef.current = voiceState;

  // An andagent:// link may prefill the composer instead of sending directly.
  useEffect(() => {
    const fill = (event: Event) => {
      const text = String((event as CustomEvent<string>).detail ?? "");
      const ta = inputRef.current;
      if (!text || !ta) return;
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype,
        "value",
      )?.set;
      setter?.call(ta, text);
      ta.dispatchEvent(new Event("input", { bubbles: true }));
      ta.focus();
    };
    window.addEventListener("andagent:composer-fill", fill);
    return () => window.removeEventListener("andagent:composer-fill", fill);
  }, []);

  const onMicClick = () => {
    if (voiceState === "idle") void startRecording();
    else if (voiceState === "recording") stopRecording();
  };

  return (
    <div className="composer-dock">
      <ComposerPrimitive.Root className="composer">
        <div className="composer-row">
          {hasKey && (
            <button
              type="button"
              className={`mic-btn ${voiceState === "recording" ? "recording" : ""}`}
              onClick={onMicClick}
              disabled={voiceState === "transcribing"}
              title={voiceState === "recording" ? t("stopRecording") : t("voiceInput")}
              aria-label={voiceState === "recording" ? t("stopRecording") : t("voiceInput")}
            >
              {voiceState === "transcribing" ? (
                <span className="mic-spinner" aria-hidden="true" />
              ) : (
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    d="M12 15a3.5 3.5 0 003.5-3.5V6a3.5 3.5 0 10-7 0v5.5A3.5 3.5 0 0012 15z"
                    fill="currentColor"
                  />
                  <path
                    d="M5.5 11.5a6.5 6.5 0 0013 0M12 18v3m-3.5 0h7"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    fill="none"
                  />
                </svg>
              )}
            </button>
          )}
          <ComposerPrimitive.Input
            ref={inputRef as never}
            className="composer-input"
            placeholder={
              voiceState === "recording"
                ? t("inputRecording")
                : voiceState === "transcribing"
                  ? t("inputTranscribing")
                  : hasKey
                    ? t("inputPlaceholder")
                    : t("modelInputUnconfigured")
            }
            minRows={1}
            maxRows={6}
            autoFocus
            submitMode="enter"
            // 触摸设备（手机输入法）按 Enter/换行键只换行，发送走发送按钮，
            // 避免输入法组词时点换行被当成提交直接发出。
            unstable_insertNewlineOnTouchEnter
          />
          {running ? (
            <button
              type="button"
              className="send-btn stop"
              onClick={() => aui.thread.cancelRun()}
              aria-label={t("stop")}
            >
              <span className="stop-square" />
            </button>
          ) : (
            <ComposerPrimitive.Send className="send-btn" aria-label={t("send")}>
              {/* 纸飞机发送图标：单色平面飞机，尺寸与语音输入图标一致（18px） */}
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none">
                <path
                  d="M22 2 2 9l9 4 4 9z"
                  fill="currentColor"
                  stroke="currentColor"
                  strokeWidth="1.2"
                  strokeLinejoin="round"
                />
              </svg>
            </ComposerPrimitive.Send>
          )}
        </div>
      </ComposerPrimitive.Root>
    </div>
  );
}

export default function App() {
  const { t } = useI18n();
  const [settings, setSettings] = useState<Settings>(() => loadSettings());
  const [showSettings, setShowSettings] = useState(false);
  const [showModelSetup, setShowModelSetup] = useState(false);
  const [modelOnboarding, setModelOnboarding] = useState(false);
  // Open after mount so StrictMode's initial effect cleanup cannot pop the
  // onboarding history entry and accidentally mark the guide as dismissed.
  useEffect(() => {
    if (needsModelSetup(settings)) { setModelOnboarding(true); setShowModelSetup(true); }
  }, []);
  const openModelSetup = () => { setModelOnboarding(false); setShowModelSetup(true); };
  // 不退场期间改 onboarding 标记：标题在滑出过程中不能变；下次打开由 openModelSetup 重置。
  const closeModelSetup = useCallback(() => { dismissModelSetup(); setShowModelSetup(false); }, []);
  const [drawerOpen, setDrawerOpen] = useState(false);
  // 设置内部的子页也由 App 记账：子页多占一条 history 记录，返回时先回设置列表。
  const [settingsPage, setSettingsPage] = useState<SettingsPage>("home");
  const settingsOnSubpage = showSettings && settingsPage !== "home";
  // 关闭时保留当前子页，让面板带着原内容滑出；下次打开前再重置到首页。
  const closeSettings = useCallback(() => setShowSettings(false), []);
  // 退场动画期间仍保持挂载，避免覆盖层瞬间消失。
  const settingsPresence = usePresence(showSettings);
  const modelPresence = usePresence(showModelSetup);
  // 返回键层级：覆盖层（设置/抽屉）打开时各占一条 history 记录——Android 返回
  // 手势/按键先收起覆盖层，而不是退出 App。depth 由 backLayers 模块持久持有，
  // effect 重跑只做 attach + sync，不会把已压入的记录丢在栈里。
  const layerMgrRef = useRef<ReturnType<typeof createBackLayerManager> | null>(null);
  const overlayRef = useRef({ showSettings, settingsOnSubpage, showModelSetup, drawerOpen });
  overlayRef.current = { showSettings, settingsOnSubpage, showModelSetup, drawerOpen };
  const closeTop = useCallback((): boolean => {
    if (overlayRef.current.showModelSetup) { closeModelSetup(); return true; }
    // 设置子页优先于设置本身出栈，避免"一次返回关掉整个设置"。
    if (overlayRef.current.settingsOnSubpage) { setSettingsPage("home"); return true; }
    if (overlayRef.current.showSettings) { closeSettings(); return true; }
    if (overlayRef.current.drawerOpen) { setDrawerOpen(false); return true; }
    return false;
  }, [closeModelSetup, closeSettings]);
  const getLayerMgr = () =>
    layerMgrRef.current ?? (layerMgrRef.current = createBackLayerManager(closeTop));
  useEffect(() => {
    const mgr = getLayerMgr();
    mgr.attach();
    mgr.sync((showModelSetup ? 1 : 0) + (showSettings ? 1 : 0) + (settingsOnSubpage ? 1 : 0) + (drawerOpen ? 1 : 0));
    return () => mgr.detach();
  }, [showSettings, settingsOnSubpage, showModelSetup, drawerOpen]);
  // 卸载（页面销毁 / StrictMode 双挂载）时清空 history 栈，避免残留记录。
  useEffect(() => {
    getLayerMgr().attach();
    return () => getLayerMgr().destroy();
  }, []);
  useEffect(() => {
    const onExported = (event: Event) => showSuccess(t("exportedTo", { path: (event as CustomEvent<string>).detail }));
    window.addEventListener("andagent:conversation-exported", onExported);
    return () => window.removeEventListener("andagent:conversation-exported", onExported);
  }, [t]);
  const version = useRef<string>("…").current;
  const engineRef = useRef<AgentEngine | null>(null);
  if (!engineRef.current) engineRef.current = new AgentEngine(settings);
  const engine = engineRef.current;

  const snapshot = useSyncExternalStore(engine.subscribe, engine.getSnapshot, engine.getSnapshot);
  const [appVersion, setAppVersion] = useState(version);
  useEffect(() => {
    setAppVersion(getAppVersion());
  }, []);

  // 请求失败统一走顶部 toast（同一条错误只弹一次，换语言不重复弹）。
  const shownErrorRef = useRef<string | null>(null);
  useEffect(() => {
    const error = snapshot.error;
    if (!error) {
      shownErrorRef.current = null;
      return;
    }
    if (shownErrorRef.current === error) return;
    shownErrorRef.current = error;
    showError(`${t("requestFailed")}：${error}`);
  }, [snapshot.error, t]);

  useEffect(() => {
    installLLMFetch(settings.baseUrl);
    engine.setSettings(settings);
  }, [engine, settings]);

  // Deep links: andagent://send?prompt=...&auto=1 (external apps / widgets).
  useEffect(() => {
    const handle = (detail: DeeplinkDetail | null) => {
      if (!detail?.prompt) return;
      if (detail.auto) void engine.send(detail.prompt);
      else window.dispatchEvent(new CustomEvent("andagent:composer-fill", { detail: detail.prompt }));
    };
    const drain = () => consumeDeeplink().forEach(handle);
    window.addEventListener("andagent:deeplink-flush", drain);
    // Links received before the JS bundle mounted are queued by MainActivity.
    const timer = window.setTimeout(drain, 250);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("andagent:deeplink-flush", drain);
    };
  }, [engine]);

  const runtime = useExternalStoreRuntime<ThreadMessageLike>({
    messages: snapshot.messages,
    convertMessage: (message) => message,
    isRunning: snapshot.running,
    onNew: async (message) => {
      const text = message.content
        .filter((part): part is { type: "text"; text: string } => part.type === "text")
        .map((part) => part.text)
        .join("\n");
      await engine.send(text);
    },
    onCancel: async () => {
      engine.stop();
    },
  });

  const saveSettings = (next: Settings) => {
    setSettings(next);
    engine.setSettings(next);
    installLLMFetch(next.baseUrl);
  };

  const hasMessages = snapshot.messages.length > 0;
  // 「对话记录」列表：本次会话里输入过的每一条消息（保持发送顺序）。
  const userInputs = useMemo<MessageNavItem[]>(() => {
    const items: MessageNavItem[] = [];
    for (const message of snapshot.messages) {
      if (message.role !== "user" || !message.id) continue;
      const text =
        typeof message.content === "string"
          ? message.content
          : message.content.map((part) => (part.type === "text" ? part.text : "")).join("\n");
      const trimmed = text.trim();
      if (trimmed) items.push({ id: message.id, text: trimmed });
    }
    return items;
  }, [snapshot.messages]);

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <div className="app">
        <header className="app-head">
          <div className="head-left">
            <button
              type="button"
              className="icon-btn"
              title={t("sessions")}
              aria-label={t("sessions")}
              onClick={() => setDrawerOpen(true)}
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  d="M4 6h16M4 12h16M4 18h16"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
            <div className="brand">
              <div className="brand-text">
                <strong>AndAgent</strong>
                <span>{hasBridge() ? `v${appVersion}` : t("browserPreviewVersion", { version: appVersion })}</span>
              </div>
            </div>
          </div>
          <div className="head-actions">
            <button
              type="button"
              className="model-chip"
              title={t("modelSettings")}
              onClick={openModelSetup}
            >
              {settings.model || t("connectModel")}
            </button>
            <button
              type="button"
              className="icon-btn"
              title={t("newChat")}
              aria-label={t("newChat")}
              disabled={!hasMessages || snapshot.running}
              onClick={() => engine.newSession()}
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  d="M12 5v14M5 12h14"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
          </div>
        </header>

        {!isModelConfigured(settings) && (
          <div className="banner">
            <span>{t("modelNotConfigured")}</span>
            <button type="button" onClick={openModelSetup}>
              {t("modelConfigure")}
            </button>
          </div>
        )}

        <ThreadPrimitive.Root className="thread">
          <ThreadPrimitive.Viewport className="viewport" autoScroll>
            <div className="thread-inner">
              <ThreadPrimitive.Empty>
                <div className="empty">
                  <h1>{t("emptyTitle")}</h1>
                  <p>{t("emptyBody")}</p>
                  <TemplateGrid />
                </div>
              </ThreadPrimitive.Empty>
              <ThreadPrimitive.Messages
                components={{
                  UserMessage,
                  AssistantMessage,
                }}
              />
            </div>
          </ThreadPrimitive.Viewport>
          {/* 会话切换时用 key 重置：面板收起，列表重新按新会话计算。 */}
          <MessageNav key={snapshot.sessionId} items={userInputs} />
        </ThreadPrimitive.Root>

        <Composer hasKey={isModelConfigured(settings)} />

        {settingsPresence.rendered && (
          <SettingsPanel
            settings={settings}
            page={settingsPage}
            closing={settingsPresence.closing}
            onNavigate={setSettingsPage}
            // 子页返回只改 UI 状态；history 层由 backLayers.sync() 收回，
            // 其程序化 back() 的回响由 pendingBacks 吸收，不会连带关掉设置本身。
            onBack={() => setSettingsPage("home")}
            onClose={closeSettings}
            onSave={saveSettings}
            onModelSetup={openModelSetup}
          />
        )}

        {modelPresence.rendered && (
          <ModelSetup
            settings={settings}
            onboarding={modelOnboarding}
            closing={modelPresence.closing}
            onClose={closeModelSetup}
            onSave={saveSettings}
          />
        )}

        <ConfirmDialog />

        <Drawer
          open={drawerOpen}
          sessions={snapshot.sessions}
          currentId={snapshot.sessionId}
          version={appVersion}
          sessionUsage={snapshot.usage.session}
          onClose={() => setDrawerOpen(false)}
          onNew={() => {
            engine.newSession();
            setDrawerOpen(false);
          }}
          onSwitch={(id) => engine.switchSession(id)}
          onRename={(id, title) => engine.renameSession(id, title)}
          onSettings={() => { setDrawerOpen(false); setSettingsPage("home"); setShowSettings(true); }}
          onDelete={(id) => engine.deleteSession(id)}
          onExport={(id) => {
            const session = snapshot.sessions.find((s) => s.id === id);
            if (!session) return;
            const result = exportConversation(session.title, conversationMarkdown(id, session.title));
            if (result.startsWith("ERROR:")) showError(result);
          }}
        />
      </div>
    </AssistantRuntimeProvider>
  );
}

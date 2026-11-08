import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
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
  consumeA2uiForms,
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
import { CopyMenu, selectedText, useLongPress, type CopyMenuAction } from "./CopyMenu";
import { Icon } from "./icons";
import { SshTransferHost } from "./SshTransferCard";
import { ResourceHubPanel } from "./ResourceHubPanel";
import { Drawer } from "./Drawer";
import { appConfirm, appPrompt, ConfirmDialog, PromptDialog } from "./ConfirmDialog";
import { batchConversationMarkdown, conversationMarkdown, hasExportableMessages } from "./exportConversation";
import { exportConversationImage } from "./exportImage";
import { MarkdownText } from "./Markdown";
import { MessageNav, type MessageNavItem } from "./MessageNav";
import { SettingsPanel, type SettingsPage } from "./Settings";
import { ModelSetup } from "./ModelSetup";
import { dismissModelSetup, isModelConfigured, needsModelSetup } from "./modelConfig";
import { pickTemplateGroups } from "./templates";
import { ToolCard } from "./ToolCard";
import { fmtTokens, sumUsageBreakdown } from "./usage";
import { SessionStore } from "./sessions";
import { useI18n } from "./i18n";
import { showError, showInfo, showResult, showSuccess } from "./toast";
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

/** R82：消息编辑/重发需要拿到引擎实例；消息组件由 assistant-ui 渲染，无法透传 props。 */
const EngineContext = createContext<AgentEngine | null>(null);

/**
 * 就地编辑已发送的消息。回车换行（与主输入框的触摸行为一致），
 * Ctrl/Cmd+Enter 或「重新发送」提交，Esc 取消。
 */
function UserEditBox({
  initial,
  busy,
  onSubmit,
  onCancel,
}: {
  initial: string;
  busy: boolean;
  onSubmit: (text: string) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [value, setValue] = useState(initial);
  const ref = useRef<HTMLTextAreaElement | null>(null);

  // 高度跟随内容，超过上限后内部滚动（与输入框的 maxRows 一致）。
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
  }, [value]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    el.setSelectionRange(el.value.length, el.value.length);
  }, []);

  const submit = () => {
    if (busy || !value.trim()) return;
    onSubmit(value);
  };

  return (
    <div className="user-edit">
      <textarea
        ref={ref}
        className="user-edit-input"
        value={value}
        rows={1}
        aria-label={t("editMessage")}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onCancel();
          } else if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
            event.preventDefault();
            submit();
          }
        }}
      />
      <div className="user-edit-actions">
        <button type="button" className="user-edit-btn" onClick={onCancel}>
          {t("cancel")}
        </button>
        <button
          type="button"
          className="user-edit-btn primary"
          disabled={busy || !value.trim()}
          onClick={submit}
        >
          {t("resend")}
        </button>
      </div>
    </div>
  );
}

function UserMessage() {
  const { t } = useI18n();
  const text = useMessageText();
  const messageId = useAuiState((state) => state.message.id);
  const edited = useAuiState(
    (state) => (state.message.metadata?.custom as { edited?: boolean } | undefined)?.edited === true,
  );
  const running = useAuiState((state) => state.thread.isRunning);
  const engine = useContext(EngineContext);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  // null = 未编辑；字符串 = 编辑草稿（点「重新发送」才截断重发）。
  const [editing, setEditing] = useState<string | null>(null);
  const longPress = useLongPress((x, y) => setMenu({ x, y }));

  const actions: CopyMenuAction[] =
    engine && !running
      ? [
          { icon: "pencil", label: t("editMessage"), onClick: () => setEditing(text) },
          {
            icon: "refresh",
            label: t("retryMessage"),
            onClick: () => {
              void (async () => {
                if (!(await appConfirm(t("retryConfirm")))) return;
                void engine.retryFrom(messageId);
              })();
            },
          },
        ]
      : [];

  const submitEdit = (value: string) => {
    if (!engine) return;
    void (async () => {
      if (!(await appConfirm(t("editConfirm")))) return;
      setEditing(null);
      void engine.editAndResend(messageId, value);
    })();
  };

  return (
    // id 供「对话记录」快捷导航定位（MessageNav 用 msg-<id> 查找节点）。
    <MessagePrimitive.Root
      id={`msg-${messageId}`}
      className="msg msg-user"
      {...(editing !== null ? {} : longPress)}
    >
      {editing !== null ? (
        <UserEditBox
          initial={editing}
          busy={running}
          onSubmit={submitEdit}
          onCancel={() => setEditing(null)}
        />
      ) : (
        <div className="user-col">
          <div className="user-bubble">
            <MessagePrimitive.Parts components={{ Text: UserText }} />
          </div>
          {edited && <div className="user-edited">{t("editedMark")}</div>}
        </div>
      )}
      {menu && text && editing === null && (
        <CopyMenu
          text={text}
          x={menu.x}
          y={menu.y}
          actions={actions}
          onClose={() => setMenu(null)}
        />
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
          {/* 用 SVG 而非 ▸ 字形：字形自带侧边距与基线偏移，旋转中心不在视觉中心。 */}
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="m9 6 6 6-6 6" />
          </svg>
        </span>
        {running ? t("thinkingNow") : t("thinking")}
      </button>
      {/* 复制按钮固定在卡片右上角，与标题栏同排。 */}
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
        </div>
      </div>
    </div>
  );
}

/**
 * 消息尾部状态：正在流出文本/思考 → 光标；其余（等首包、工具执行中、
 * 工具已返回但下一段回复还没到）→ 三点等待指示。
 */
function useAssistantTail(): "streaming" | "waiting" {
  return useAuiState((state) => {
    const content = state.message.content;
    const last = content[content.length - 1] as { type?: string; text?: string } | undefined;
    if (last?.type === "text" && (last.text ?? "").trim().length > 0) return "streaming";
    if (last?.type === "reasoning" && (last.text ?? "").trim().length > 0) return "streaming";
    return "waiting";
  });
}

function AssistantMessage() {
  const { t } = useI18n();
  const running = useAuiState((state) => state.message.status?.type === "running");
  const tail = useAssistantTail();
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
          (tail === "streaming" ? (
            <span className="stream-cursor" aria-hidden="true" />
          ) : (
            <span className="stream-typing" role="status" aria-label={t("thinkingNow")}>
              <span className="stream-dot" />
              <span className="stream-dot" />
              <span className="stream-dot" />
            </span>
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

/** 线程在跑、但还没有任何消息承载等待指示（例如刚发送、首包未到）。 */
function ThreadWaitingHint() {
  const { t } = useI18n();
  const waiting = useAuiState((state) => {
    if (!state.thread.isRunning) return false;
    const last = state.thread.messages[state.thread.messages.length - 1];
    return !last || last.role !== "assistant" || last.status.type !== "running";
  });
  if (!waiting) return null;
  return (
    <div className="stream-typing thread-waiting" role="status" aria-label={t("thinkingNow")}>
      <span className="stream-dot" />
      <span className="stream-dot" />
      <span className="stream-dot" />
    </div>
  );
}

function TemplateGrid() {
  const { language, t } = useI18n();
  const aui = useAui();
  // 每次进入空会话都重新抽一批，挂载时只随机一次，切换语言不会打乱。
  const [groups, setGroups] = useState(() => pickTemplateGroups());
  return (
    <div className="template-groups">
      {groups.map((group) => (
        <div className="template-group" key={group.label.zh}>
          <div className="template-group-label">{group.label[language]}</div>
          <div className="template-grid">
            {group.templates.map((tpl) => (
              <button
                type="button"
                className="template-card"
                key={tpl.title.zh}
                title={tpl.prompt[language]}
                onClick={() =>
                  aui.thread.append({
                    role: "user",
                    content: [{ type: "text", text: tpl.prompt[language] }],
                  })
                }
              >
                <span className="template-icon" aria-hidden="true">
                  <Icon name={tpl.icon} size={15} />
                </span>
                <span className="template-title">{tpl.title[language]}</span>
              </button>
            ))}
          </div>
        </div>
      ))}
      <button
        type="button"
        className="template-refresh"
        onClick={() => setGroups(pickTemplateGroups())}
      >
        <Icon name="refresh" size={13} />
        <span>{t("templateShuffle")}</span>
      </button>
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
                <span className="stream-typing mic-typing" aria-hidden="true">
                  <span className="stream-dot" />
                  <span className="stream-dot" />
                  <span className="stream-dot" />
                </span>
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
  const [resourceHubOpen, setResourceHubOpen] = useState(false);
  // 顶栏「更多」菜单的位置（点按钮时按按钮矩形定位，点空白处收起）。
  const [moreMenu, setMoreMenu] = useState<{ x: number; y: number } | null>(null);
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
  const overlayRef = useRef({ showSettings, settingsOnSubpage, showModelSetup, drawerOpen, resourceHubOpen });
  overlayRef.current = { showSettings, settingsOnSubpage, showModelSetup, drawerOpen, resourceHubOpen };
  const closeTop = useCallback((): boolean => {
    if (overlayRef.current.resourceHubOpen) { setResourceHubOpen(false); return true; }
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
    mgr.sync((showModelSetup ? 1 : 0) + (showSettings ? 1 : 0) + (settingsOnSubpage ? 1 : 0) + (drawerOpen ? 1 : 0) + (resourceHubOpen ? 1 : 0));
    return () => mgr.detach();
  }, [showSettings, settingsOnSubpage, showModelSetup, drawerOpen, resourceHubOpen]);
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
    const drainLinks = () => consumeDeeplink().forEach(handle);
    // R105: cron a2ui forms are injected deterministically as an assistant
    // message (no LLM round-trip). a2ui_submit still returns via composer-fill.
    // 本轮 LLM 进行中时**不要**消费收件箱：consumeA2uiForms 是读后即删，而
    // appendA2uiForm 不会把消息 splice 进 running turn，边跑边 drain 会丢表单。
    // 让 fence 继续留在 filesDir/cron_a2ui，空闲后（重试）再补渲染，兼顾持久化。
    let a2uiRetry: number | undefined;
    const drainForms = () => {
      if (engine.isRunning) {
        if (a2uiRetry === undefined) {
          a2uiRetry = window.setTimeout(() => {
            a2uiRetry = undefined;
            drainForms();
          }, 500);
        }
        return;
      }
      consumeA2uiForms().forEach((fence) => engine.appendA2uiForm(fence));
    };
    window.addEventListener("andagent:deeplink-flush", drainLinks);
    window.addEventListener("andagent:a2ui-flush", drainForms);
    // R97 fixup: SSH 通知点击 → MainActivity 广播 andagent:session-switch → 切会话。
    const switchByEvent = (event: Event) => {
      const id = String((event as CustomEvent<string>).detail ?? "");
      if (!id) return;
      engine.switchSession(id);
      setDrawerOpen(false);
    };
    window.addEventListener("andagent:session-switch", switchByEvent);
    // R104: 卡片上的「停止」——工具已不在跑时也要把卡片落成中断态。
    const cancelToolCard = (event: Event) => {
      engine.cancelToolCall(String((event as CustomEvent<string>).detail ?? ""));
    };
    window.addEventListener("andagent:tool-cancel", cancelToolCard);
    // Links/forms received before the JS bundle mounted are queued natively.
    const timer = window.setTimeout(() => { drainLinks(); drainForms(); }, 250);
    return () => {
      window.clearTimeout(timer);
      if (a2uiRetry !== undefined) window.clearTimeout(a2uiRetry);
      window.removeEventListener("andagent:deeplink-flush", drainLinks);
      window.removeEventListener("andagent:a2ui-flush", drainForms);
      window.removeEventListener("andagent:session-switch", switchByEvent);
      window.removeEventListener("andagent:tool-cancel", cancelToolCard);
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

  // R95: 会话导出长图（相册 + 可选直接分享）。标题取自会话索引，缺省用「新对话」。
  const exportLongImage = (id: string, share = false) => {
    const title = snapshot.sessions.find((item) => item.id === id)?.title ?? t("newChat");
    showInfo(t(share ? "longImageSharing" : "longImageExporting"));
    void exportConversationImage(id, title, { share }).then((result) => showResult(result));
  };
  // 导出数据（Markdown）：抽屉条目菜单与顶栏「更多」共用。空会话禁止导出。
  const exportSessionMarkdown = (id: string) => {
    const session = snapshot.sessions.find((item) => item.id === id);
    if (!session || !hasExportableMessages(id)) return;
    const result = exportConversation(session.title, conversationMarkdown(id, session.title));
    if (result.startsWith("ERROR:")) showError(result);
  };
  // 顶栏「更多」：当前会话的导出数据 / 导出长图 / 编辑（改标题）/ 删除，收回同一个菜单。
  // 空会话没有任何可操作内容：四项全部置灰，发出第一条消息后自动恢复。
  const hasMessages = snapshot.messages.length > 0;
  const currentTitle =
    snapshot.sessions.find((item) => item.id === snapshot.sessionId)?.title ?? t("newChat");
  // 「更多」顶部的 Token 明细：读原始 transcript（UI 消息的 metadata 只带 input/output）。
  const tokenBreakdown = useMemo(
    () => sumUsageBreakdown(SessionStore.load(snapshot.sessionId)),
    [snapshot.sessionId, snapshot.messages],
  );
  const tokenInfo = useMemo(() => {
    const num = (value: number) => value.toLocaleString();
    const total = tokenBreakdown.totalTokens || tokenBreakdown.input + tokenBreakdown.output;
    return [
      { label: t("input"), value: num(tokenBreakdown.input) },
      { label: t("output"), value: num(tokenBreakdown.output) },
      { label: t("tokenCacheRead"), value: num(tokenBreakdown.cacheRead) },
      { label: t("tokenCacheWrite"), value: num(tokenBreakdown.cacheWrite) },
      { label: t("tokenTotal"), value: num(total) },
      { label: t("tokenTurns"), value: num(tokenBreakdown.turns) },
    ];
  }, [tokenBreakdown, t]);
  const moreActions: CopyMenuAction[] = [
    {
      icon: "download",
      label: t("exportMarkdown"),
      disabled: !hasMessages,
      onClick: () => exportSessionMarkdown(snapshot.sessionId),
    },
    {
      icon: "image",
      label: t("exportLongImage"),
      disabled: !hasMessages,
      onClick: () => exportLongImage(snapshot.sessionId),
    },
    {
      icon: "pencil",
      label: t("rename"),
      disabled: !hasMessages,
      onClick: () => {
        void appPrompt(t("renameSession", { name: currentTitle }), currentTitle).then((title) => {
          const next = title?.trim();
          if (next && next !== currentTitle) engine.renameSession(snapshot.sessionId, next);
        });
      },
    },
    {
      icon: "trash",
      label: t("delete"),
      disabled: !hasMessages,
      onClick: () => {
        void appConfirm(t("confirmDeleteSession", { name: currentTitle })).then((ok) => {
          if (ok) engine.deleteSession(snapshot.sessionId);
        });
      },
    },
  ];
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
    <EngineContext.Provider value={engine}>
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
              <button type="button" className="icon-btn" title={t("resourceHub")} aria-label={t("resourceHub")} onClick={() => setResourceHubOpen(true)}>🧩</button>
              <button
                type="button"
                className="model-chip"
                title={t("modelSettings")}
                onClick={openModelSetup}
              >
                {settings.model || t("connectModel")}
              </button>
              {/* 会话切换时用 key 重置：面板收起，列表重新按新会话计算。 */}
              <MessageNav key={snapshot.sessionId} items={userInputs} />
              <button
                type="button"
                className={`head-chip ${moreMenu ? "open" : ""}`}
                title={t("more")}
                aria-label={t("more")}
                aria-haspopup="menu"
                aria-expanded={moreMenu !== null}
                onClick={(event) => {
                  if (moreMenu) {
                    setMoreMenu(null);
                    return;
                  }
                  const rect = event.currentTarget.getBoundingClientRect();
                  setMoreMenu({ x: rect.right - 150, y: rect.bottom + 6 });
                }}
              >
                {/* 横向三点「更多」：与顶栏其它图标同一套描边圆头笔触（纯图标，无文案）。 */}
                <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true" fill="none">
                  <path
                    d="M6 12h.01M12 12h.01M18 12h.01"
                    stroke="currentColor"
                    strokeWidth="2.8"
                    strokeLinecap="round"
                  />
                </svg>
              </button>
            </div>
          </header>
          {resourceHubOpen && <ResourceHubPanel onClose={() => setResourceHubOpen(false)} />}

          {moreMenu && (
            <CopyMenu
              x={moreMenu.x}
              y={moreMenu.y}
              onClose={() => setMoreMenu(null)}
              actions={moreActions}
              info={tokenInfo}
              infoTitle={t("tokenUsageTitle")}
            />
          )}

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
                <ThreadWaitingHint />
              </div>
            </ThreadPrimitive.Viewport>
          </ThreadPrimitive.Root>

          <SshTransferHost />
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
          <PromptDialog />

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
            onDeleteMany={(ids) => engine.deleteSessions(ids)}
            onExportMany={(ids) => {
              const selected = ids.flatMap((id) => {
                const session = snapshot.sessions.find((s) => s.id === id);
                return session ? [session] : [];
              });
              if (selected.length === 0) return;
              const today = new Date();
              const date = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
              const title = selected.length === 1
                ? selected[0].title
                : `conversations-batch-${date}`;
              const markdown = selected.length === 1
                ? conversationMarkdown(selected[0].id, selected[0].title)
                : batchConversationMarkdown(selected);
              const result = exportConversation(title, markdown);
              if (result.startsWith("ERROR:")) showError(result);
            }}
            onExport={exportSessionMarkdown}
            onExportImage={(id) => exportLongImage(id)}
            onShareImage={(id) => exportLongImage(id, true)}
          />
        </div>
      </AssistantRuntimeProvider>
    </EngineContext.Provider>
  );
}

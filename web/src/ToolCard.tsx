import { useI18n } from "./i18n";
import { useEffect, useMemo, useState } from "react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";
import { cancelToolByCallId } from "./bridge";
import { selectedText } from "./CopyMenu";
import { WebViewCard } from "./WebViewCard";
import { MediaCard, mediaFromResult } from "./MediaCard";
import { PhotosCard } from "./RichCards";
import { TOOL_META } from "./toolMeta";

const COLLAPSE_LINES = 15;

function formatArgs(args: unknown, noArgs: string): string {
  if (!args || (typeof args === "object" && Object.keys(args).length === 0)) {
    return noArgs;
  }
  try {
    return JSON.stringify(args, null, 2);
  } catch {
    return String(args);
  }
}

/** Pretty-print JSON tool results; everything else is shown verbatim. */
function formatResult(raw: string): { text: string; json: boolean } {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
    return { text: raw, json: false };
  }
  try {
    return { text: JSON.stringify(JSON.parse(trimmed), null, 2), json: true };
  } catch {
    return { text: raw, json: false };
  }
}

function summarize(raw: string, terms: { array: string; items: string }): string {
  const trimmed = raw.trim();
  if (!trimmed) return "";
  try {
    if (trimmed.startsWith("{")) {
      const obj = JSON.parse(trimmed) as Record<string, unknown>;
      const listKey = Object.keys(obj).find((key) => Array.isArray(obj[key]));
      if (listKey) {
        const count = (obj[listKey] as unknown[]).length;
        const total = typeof obj.count === "number" ? obj.count : count;
        return `${listKey}：${total} ${terms.items}`;
      }
      const pairs = Object.keys(obj)
        .slice(0, 3)
        .map((key) => {
          const value = obj[key];
          return `${key}=${typeof value === "object" ? JSON.stringify(value) : String(value)}`;
        })
        .join(" · ");
      return pairs.slice(0, 120);
    }
    if (trimmed.startsWith("[")) {
      const arr = JSON.parse(trimmed) as unknown[];
      return `${terms.array}：${arr.length} ${terms.items}`;
    }
  } catch {
    /* not JSON — fall through */
  }
  return trimmed
    .split("\n")
    .filter((line) => line.trim())
    .slice(0, 2)
    .join(" · ")
    .slice(0, 120);
}

function JsonNode({ label, value, depth = 0 }: { label: string; value: unknown; depth?: number }) {
  const [open, setOpen] = useState(depth < 1);
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    return <div className="json-node" style={{ marginLeft: depth ? 12 : 0 }}>
      <button
        type="button"
        onClick={() => {
          if (selectedText()) return;
          setOpen(!open);
        }}
      >
        {open ? "▾" : "▸"} {label} <span>{Array.isArray(value) ? `[${entries.length}]` : `{${entries.length}}`}</span>
      </button>
      {open && entries.map(([key, child]) => <JsonNode key={key} label={key} value={child} depth={depth + 1} />)}
    </div>;
  }
  return <div className="json-leaf" style={{ marginLeft: depth * 12 }}><span>{label}:</span> {JSON.stringify(value)}</div>;
}

const SLOW_HINT_SECONDS = 20;

export function ToolCard(props: ToolCallMessagePartProps) {
  const { t, language } = useI18n();
  const running = props.result === undefined;
  const failed = !!props.isError;
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const [notice, setNotice] = useState("");
  const [elapsed, setElapsed] = useState(0);

  // 工具没有返回时给出可见的耗时，避免"以为卡住了"。
  useEffect(() => {
    if (!running) return;
    const startedAt = Date.now();
    setElapsed(0);
    const timer = window.setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt) / 1000));
    }, 500);
    return () => window.clearInterval(timer);
  }, [running]);
  useEffect(() => {
    if (!props.toolName.startsWith("nfc_")) return;
    const listener = (event: Event) => setNotice((event as CustomEvent<string>).detail);
    window.addEventListener("andagent:notice", listener);
    return () => window.removeEventListener("andagent:notice", listener);
  }, [props.toolName]);

  useEffect(() => {
    setOpen(running);
  }, [running]);

  const meta = TOOL_META[props.toolName] ?? { label: props.toolName, icon: "⚙️" };
  const raw = String(props.result ?? "");
  const interrupted = raw.includes("已被用户中断");
  const formatted = useMemo(() => formatResult(raw), [raw]);
  const lines = formatted.text.split("\n");
  const canFold = lines.length > COLLAPSE_LINES;
  const shown = canFold && !expanded ? lines.slice(0, COLLAPSE_LINES).join("\n") : formatted.text;
  const written = /^(WRITTEN|DELETED|TOGGLED)/.test(raw);
  let captureImage: string | null = null;
  if (["screen_capture", "image_edit", "image_preview", "image_collage", "devtools_screenshot"].includes(props.toolName) && !running && !failed) {
    try { const result = JSON.parse(raw) as { thumbnailBase64?: string; thumbnailMime?: string }; if (result.thumbnailBase64) captureImage = `data:${result.thumbnailMime ?? "image/jpeg"};base64,${result.thumbnailBase64}`; } catch { /* no image */ }
  }
  let beforeImage: string | null = null;
  if (["image_edit", "image_preview"].includes(props.toolName) && captureImage) {
    try { const result = JSON.parse(raw) as { sourceThumbnailBase64?: string }; if (result.sourceThumbnailBase64) beforeImage = `data:image/png;base64,${result.sourceThumbnailBase64}`; } catch { /* no image */ }
  }

  const preview = running ? (props.toolName.startsWith("nfc_") && !["nfc_status", "nfc_hce_status", "nfc_keys_manage"].includes(props.toolName) ? t("toolNfcWaiting") : t("toolRunning")) : summarize(raw, { array: t("array"), items: t("items") });
  const parsed = useMemo(() => { try { return formatted.json ? JSON.parse(raw) : null; } catch { return null; } }, [raw, formatted.json]);
  const media = !running && !failed && ["file_download", "tts_to_file", "mic_record", "screen_record_h264", "screen_record", "screen_record_stop"].includes(props.toolName) ? mediaFromResult(raw) : null;
  const webUri = props.toolName === "web_embed" && parsed?.type === "webview" && typeof parsed.uri === "string" ? parsed.uri : null;
  const photoGrid = !running && !failed && props.toolName === "album_photos" && Array.isArray(parsed?.photos) && parsed.photos.length > 0;

  return (
    <div
      className={`tool-card${failed ? " tool-error" : ""}${running ? " tool-running" : ""}${
        meta.destructive ? " tool-destructive" : ""
      }`}
    >
      {running && <div className="tool-progress" aria-hidden="true"><span /></div>}
      <button
        type="button"
        className="tool-summary"
        onClick={() => {
          // 划选标题/预览文字时不要误触折叠。
          if (selectedText()) return;
          setOpen((v) => !v);
        }}
      >
        <span className="tool-icon" aria-hidden="true">
          {meta.icon}
        </span>
        <span className="tool-name">{language === "en" ? meta.labelEn ?? meta.label : meta.label}</span>
        {meta.destructive && <span className="tool-badge">{t("toolWriting")}</span>}
        <span className="tool-preview">{preview}</span>
        <span className={`tool-state${failed ? " state-error" : ""}`}>
          {running ? (
            <>
              <span className="dot-pulse" /> {t("toolRunningState")}
              {elapsed > 0 && <span className="tool-elapsed">{elapsed}s</span>}
            </>
          ) : interrupted ? (
            t("toolInterrupted")
          ) : failed ? (
            t("toolFailed")
          ) : (
            t("toolDone")
          )}
        </span>
        <span className={`tool-chevron${open ? " open" : ""}`}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="m6 9 6 6 6-6" />
          </svg>
        </span>
      </button>

      {running && notice && <div className="tool-confirm">{notice}</div>}

      {running && (
        <div className="tool-cancel-row">
          <button
            type="button"
            className="tool-stop"
            onClick={() => cancelToolByCallId(props.toolCallId)}
          >
            {t("stopAction")}
          </button>
        </div>
      )}

      {written && !failed && !running && (
        <div className="tool-confirm">
          <span aria-hidden="true">✓</span>
          <span>{t("toolWritten")}</span>
        </div>
      )}

      {webUri && <WebViewCard uri={webUri} />}
      {photoGrid && <PhotosCard code={JSON.stringify(parsed?.photos ?? [])} />}
      {media && <MediaCard kind={media.kind} uri={media.uri} />}
      {captureImage && <div className="tool-image-comparison">{beforeImage && <figure><img src={beforeImage} alt={t("beforeEdit")} /><figcaption>{t("beforeEdit")}</figcaption></figure>}<figure><img src={captureImage} alt={t("afterEdit")} /><figcaption>{beforeImage ? t("afterEdit") : t("imagePreview")}</figcaption></figure></div>}

      {/* 常驻挂载 + 网格行高过渡：详情展开/收起是滑动而不是瞬断。 */}
      <div className={`tool-body-wrap${open ? " open" : ""}`} aria-hidden={!open}>
        <div className="tool-body-inner">
          <div className="tool-body">
            <div className="tool-section">
              <div className="tool-label">{t("arguments")}</div>
              <pre className="tool-pre">{formatArgs(props.args, t("noArgs"))}</pre>
            </div>
            {running && (
              <div className="tool-section">
                <div className="tool-label">
                  <span className="tool-label-text">{t("result")}</span>
                  <span className="tool-waiting">
                    {elapsed >= SLOW_HINT_SECONDS
                      ? t("toolSlowHint", { seconds: elapsed })
                      : t("toolElapsed", { seconds: elapsed })}
                  </span>
                </div>
                <div className="tool-skeleton" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </div>
              </div>
            )}
            {!running && (
              <div className="tool-section">
                <div className="tool-label">
                  <span className="tool-label-text">
                    {t("result")}{formatted.json ? " · JSON" : ""}
                    {canFold ? ` · ${lines.length} ${t("rows")}` : ""}
                  </span>
                  <button
                    type="button"
                    className="tool-copy"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText(raw);
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
                {formatted.json && parsed !== null && <details className="json-tree"><summary>{t("treeView")}</summary><JsonNode label="result" value={parsed} /></details>}
                <pre className="tool-pre tool-result">{captureImage ? t("screenshotSaved") : shown}</pre>
                {canFold && (
                  <button
                    type="button"
                    className="tool-expand"
                    onClick={() => setExpanded((v) => !v)}
                  >
                    {expanded ? t("collapse") : t("expandMore", { count: lines.length - COLLAPSE_LINES })}
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

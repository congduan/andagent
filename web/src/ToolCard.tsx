import { useI18n } from "./i18n";
import { useEffect, useMemo, useState } from "react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";
import { cancelToolByCallId } from "./bridge";
import { selectedText } from "./CopyMenu";
import { WebViewCard } from "./WebViewCard";
import { MediaCard, mediaFromResult } from "./MediaCard";
import { PhotosCard } from "./RichCards";

interface ToolMeta {
  label: string;
  labelEn?: string;
  icon: string;
  destructive?: boolean;
}

const META: Record<string, ToolMeta> = {
  get_time: { label: "获取时间", labelEn: "Get time", icon: "🕐" },
  tts_to_file: { label: "文本转音频", labelEn: "Text to audio", icon: "🎵" },
  db_create: { label: "创建数据库", labelEn: "Create database", icon: "🗄️" },
  db_list: { label: "数据库列表", labelEn: "List databases", icon: "🗄️" },
  db_gui: { label: "打开数据库", labelEn: "Open database", icon: "🗄️" },
  db_tables: { label: "数据表列表", labelEn: "List tables", icon: "🗄️" },
  db_schema: { label: "数据表结构", labelEn: "Table schema", icon: "🗄️" },
  db_exec: { label: "执行 SQL", labelEn: "Run SQL", icon: "🗄️", destructive: true },
  memory_add: { label: "保存记忆", labelEn: "Save memory", icon: "🧠" },
  memory_search: { label: "搜索记忆", labelEn: "Search memory", icon: "🧠" },
  memory_update: { label: "更新记忆", labelEn: "Update memory", icon: "🧠" },
  memory_delete: { label: "删除记忆", labelEn: "Delete memory", icon: "🧠", destructive: true },
  memory_delete_all: { label: "清空记忆", labelEn: "Clear memory", icon: "🧠", destructive: true },
  memory_get_all: { label: "记忆列表", labelEn: "List memories", icon: "🧠" },
  memory_history: { label: "记忆历史", labelEn: "Memory history", icon: "🧠" },
  memory_stats: { label: "记忆容量", labelEn: "Memory capacity", icon: "🧠" },
  db_query: { label: "查询数据库", labelEn: "Query database", icon: "🗄️" },
  db_drop: { label: "删除数据库", labelEn: "Delete database", icon: "🗑️", destructive: true },
  nfc_status: { label: "NFC 状态", labelEn: "NFC status", icon: "📶" },
  ir_available: { label: "红外状态", labelEn: "IR status", icon: "📡" },
  ir_send_raw: { label: "发射红外", labelEn: "Send IR", icon: "📡", destructive: true },
  ir_send_pronto: { label: "发送遥控码", labelEn: "Send remote code", icon: "📡", destructive: true },
  nfc_read: { label: "读取 NFC", labelEn: "Read NFC", icon: "📶" },
  nfc_write_text: { label: "写入 NFC 文本", labelEn: "Write NFC text", icon: "📶", destructive: true },
  nfc_write_uri: { label: "写入 NFC 链接", labelEn: "Write NFC link", icon: "📶", destructive: true },
  nfc_mf_dump: { label: "MIFARE 扇区 dump", labelEn: "Dump MIFARE sectors", icon: "📶" },
  nfc_mf_write_block: { label: "MIFARE 写块", labelEn: "Write MIFARE block", icon: "📶", destructive: true },
  nfc_ul_read: { label: "Ultralight 读页", labelEn: "Read Ultralight page", icon: "📶" },
  nfc_ul_write_page: { label: "Ultralight 写页", labelEn: "Write Ultralight page", icon: "📶", destructive: true },
  nfc_keys_manage: { label: "NFC key 库", labelEn: "NFC key library", icon: "🔑" },
  web_embed: { label: "内嵌浏览器", labelEn: "Embedded browser", icon: "🌐" },
  device_info: { label: "设备信息", labelEn: "Device info", icon: "📱" },
  storage_scan: { label: "存储扫描", labelEn: "Scan storage", icon: "💾" },
  album_stats: { label: "相册统计", labelEn: "Photo library stats", icon: "🖼️" },
  album_photos: { label: "相册照片", labelEn: "Photo library photos", icon: "📸" },
  album_open: { label: "图库打开", labelEn: "Open gallery", icon: "🖼️" },
  calendar_list: { label: "日历账户", labelEn: "Calendar accounts", icon: "📅" },
  calendar_events: { label: "日程查询", labelEn: "Find events", icon: "🗓️" },
  calendar_add: { label: "创建日程", labelEn: "Create event", icon: "➕", destructive: true },
  calendar_delete: { label: "删除日程", labelEn: "Delete event", icon: "🗑️", destructive: true },
  alarm_set: { label: "设置闹钟", labelEn: "Set alarm", icon: "⏰" },
  timer_set: { label: "设置计时器", labelEn: "Set timer", icon: "⏱️" },
  battery_detail: { label: "电池详情", labelEn: "Battery details", icon: "🔋" },
  wifi_info: { label: "Wi-Fi 信息", labelEn: "Wi-Fi info", icon: "📶" },
  clipboard_write: { label: "写入剪贴板", labelEn: "Write clipboard", icon: "📋" },
  clipboard_read: { label: "读取剪贴板", labelEn: "Read clipboard", icon: "📋" },
  notify_send: { label: "发送通知", labelEn: "Send notification", icon: "🔔" },
  app_list: { label: "应用列表", labelEn: "List apps", icon: "📦" },
  bt_scan: { label: "蓝牙扫描", labelEn: "Scan Bluetooth", icon: "📡" },
  bt_paired_list: { label: "已配对设备", labelEn: "Paired devices", icon: "🔵" },
  bt_connect: { label: "蓝牙连接", labelEn: "Connect Bluetooth", icon: "🔵" },
  bt_connect_direct: { label: "蓝牙直接连接", labelEn: "Direct Bluetooth connection", icon: "🔵", destructive: true },
  bt_toggle: { label: "蓝牙开关", labelEn: "Toggle Bluetooth", icon: "🔵", destructive: true },
  wifi_connect: { label: "Wi-Fi 面板", labelEn: "Wi-Fi panel", icon: "📶" },
  wifi_connect_strong: { label: "Wi-Fi 连接", labelEn: "Connect Wi-Fi", icon: "📶", destructive: true },
  wifi_saved_list: { label: "已存网络", labelEn: "Saved networks", icon: "📶" },
  wifi_forget: { label: "遗忘网络", labelEn: "Forget network", icon: "📶", destructive: true },
  wifi_toggle: { label: "Wi-Fi 开关", labelEn: "Toggle Wi-Fi", icon: "📶", destructive: true },
  termux_setup: { label: "脚本环境", labelEn: "Script environment", icon: "⌨️" },
  js_eval: { label: "QuickJS 脚本", labelEn: "QuickJS script", icon: "📜" },
  script_save: { label: "保存脚本", labelEn: "Save script", icon: "💾" },
  script_list: { label: "脚本库", labelEn: "Script library", icon: "📜" },
  script_read: { label: "读取脚本", labelEn: "Read script", icon: "📄" },
  script_run: { label: "运行脚本", labelEn: "Run script", icon: "▶️" },
  script_delete: { label: "删除脚本", labelEn: "Delete script", icon: "🗑️", destructive: true },
  script_check: { label: "检查语法", labelEn: "Check syntax", icon: "✓" },
  json_fix: { label: "修复 JSON", labelEn: "Fix JSON", icon: "🔧" },
  shell_exec: { label: "运行脚本", labelEn: "Run script", icon: "⌨️" },
  pkg_install: { label: "安装软件包", labelEn: "Install package", icon: "📦", destructive: true },
  ssh_connect: { label: "SSH 连接", labelEn: "SSH connect", icon: "🔐" },
  ssh_exec: { label: "SSH 执行", labelEn: "SSH exec", icon: "🖥️" },
  ssh_list: { label: "SSH 会话", labelEn: "SSH sessions", icon: "🖥️" },
  ssh_close: { label: "SSH 断开", labelEn: "SSH close", icon: "🔌" },
  ssh_download: { label: "SSH 下载", labelEn: "SSH download", icon: "📥" },
  ssh_upload: { label: "SSH 上传", labelEn: "SSH upload", icon: "📤" },
  ssh_remote_ls: { label: "远程文件", labelEn: "Remote files", icon: "📂" },
  credential_list: { label: "SSH 凭据", labelEn: "SSH credentials", icon: "🗝️" },
  credential_delete: { label: "删除 SSH 凭据", labelEn: "Delete SSH credential", icon: "🗝️", destructive: true },
  screen_capture: { label: "屏幕截图", labelEn: "Screenshot", icon: "🖼️" },
  image_edit: { label: "图片编辑", labelEn: "Edit image", icon: "🎨" },
  image_preview: { label: "图片预览", labelEn: "Preview image", icon: "🖼️" },
  image_info: { label: "图片信息", labelEn: "Image info", icon: "ℹ️" },
  image_collage: { label: "图片拼图", labelEn: "Image collage", icon: "🧩" },
  screen_record_h264: { label: "H.264 录屏", labelEn: "H.264 screen recording", icon: "🎥" },
  input_tap: { label: "点击屏幕", labelEn: "Tap screen", icon: "👆", destructive: true },
  input_swipe: { label: "滑动屏幕", labelEn: "Swipe screen", icon: "👆", destructive: true },
  input_text: { label: "输入文本", labelEn: "Enter text", icon: "⌨️", destructive: true },
  open_settings: { label: "打开系统设置", labelEn: "Open system settings", icon: "⚙️" },
  open_uri: { label: "跳转应用", labelEn: "Open app link", icon: "🔗" },
  phone_call: { label: "拨打电话", labelEn: "Make call", icon: "📞" },
  location_get: { label: "获取位置", labelEn: "Get location", icon: "📍" },
  location_status: { label: "定位状态", labelEn: "Location status", icon: "🛰️" },
  sms_send: { label: "发送短信", labelEn: "Send SMS", icon: "💬" },
  mic_record: { label: "麦克风录音", labelEn: "Record audio", icon: "🎤" },
  speech_recognize: { label: "语音识别", labelEn: "Speech to text", icon: "🗣️" },
  camera_overlay_show: { label: "打开相机浮窗", labelEn: "Open camera overlay", icon: "📷" },
  camera_overlay_update: { label: "调整相机浮窗", labelEn: "Adjust camera overlay", icon: "📷" },
  camera_overlay_hide: { label: "关闭相机浮窗", labelEn: "Close camera overlay", icon: "📷" },
  mic_list: { label: "录音列表", labelEn: "List recordings", icon: "🎵" },
  screen_orientation_get: { label: "屏幕方向", labelEn: "Screen orientation", icon: "🧭" },
  screen_orientation_set: { label: "旋转屏幕", labelEn: "Rotate screen", icon: "🔄" },
  otg_status: { label: "OTG 状态", labelEn: "OTG status", icon: "🔌" },
  otg_request: { label: "OTG 授权", labelEn: "Grant OTG access", icon: "🔑" },
  otg_file_ops: { label: "U 盘文件", labelEn: "USB drive files", icon: "💾", destructive: true },
  screen_record: { label: "录屏", labelEn: "Record screen", icon: "🎥" },
  screen_record_stop: { label: "停止录屏", labelEn: "Stop recording", icon: "⏹️" },
  screen_record_list: { label: "录屏列表", labelEn: "List screen recordings", icon: "🎞️" },
  file_download: { label: "下载文件", labelEn: "Download file", icon: "📥" },
  file_search: { label: "搜索文件", labelEn: "Search files", icon: "🔎" },
  file_write: { label: "写文件", labelEn: "Write file", icon: "📝", destructive: true },
  file_read: { label: "读文件", labelEn: "Read file", icon: "📄" },
  file_list: { label: "列文件", labelEn: "List files", icon: "📂" },
  sensor_list: { label: "传感器列表", labelEn: "List sensors", icon: "🧭" },
  sensor_read: { label: "读传感器", labelEn: "Read sensor", icon: "🧭" },
  steps_today: { label: "今日步数", labelEn: "Today’s steps", icon: "👟" },
  steps_history: { label: "步数历史", labelEn: "Step history", icon: "👟" },
};

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

  const meta = META[props.toolName] ?? { label: props.toolName, icon: "⚙️" };
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

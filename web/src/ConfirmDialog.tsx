import { useEffect, useRef, useState } from "react";
import { currentLanguage, useI18n } from "./i18n";
import { A2uiCard } from "./A2uiCard";

export interface ConfirmRequest {
  message: string;
  resolve: (ok: boolean) => void;
}

// R106: 富决策确认——宿主把 a2ui spec 文本交给弹窗，由 A2uiCard 渲染并回传 payload。
// 完整参数由宿主自行渲染（不进 spec、不受 A2uiCard 的 1000 字 clip），argsText/argsLabel
// 随请求一并保存，供富确认分支在 A2uiCard 下方展示全文。
export interface RichConfirmRequest {
  specText: string;
  argsText: string;
  argsLabel: string;
  resolve: (payload: Record<string, unknown> | null) => void;
}

// 单一 pending 同时容纳文本确认与富确认（判别联合）。id 用于给内嵌 A2uiCard
// 做 key：排队的新请求接管旧弹窗时强制重挂载，避免沿用上一次的决策选中态。
type Pending =
  | (ConfirmRequest & { kind: "text"; id: number })
  | (RichConfirmRequest & { kind: "rich"; id: number });

let pending: Pending | null = null;
let nextId = 1;
const listeners = new Set<(r: Pending | null) => void>();

/** 拒绝当前 pending（文本 → false，富确认 → null），不广播，供新请求接管。 */
function rejectPending(): void {
  if (!pending) return;
  const req = pending;
  pending = null;
  if (req.kind === "text") req.resolve(false);
  else req.resolve(null);
}

/** App-wide window.confirm replacement: custom dialog, no WebView URL banner. */
export function appConfirm(message: string): Promise<boolean> {
  return new Promise((resolve) => {
    rejectPending(); // auto-cancel any earlier unresolved dialog
    pending = { kind: "text", id: nextId++, message, resolve };
    listeners.forEach((l) => l(pending));
  });
}

/**
 * R106: 富决策确认。specText 为 ```a2ui JSON；argsText/argsLabel 为宿主自渲染的
 * 完整参数，不进 spec。resolve(payload) = 用户提交了决策，resolve(null) =
 * 取消/拒绝（含被新请求接管、用户点停止）。
 */
export function appRichConfirm(
  specText: string,
  argsText: string,
  argsLabel: string,
): Promise<Record<string, unknown> | null> {
  return new Promise((resolve) => {
    rejectPending(); // 新请求接管旧请求
    pending = { kind: "rich", id: nextId++, specText, argsText, argsLabel, resolve };
    listeners.forEach((l) => l(pending));
  });
}

/**
 * 关闭当前确认弹窗并按「拒绝」处理。用户点「停止」时调用：
 * 否则工具会一直卡在确认弹窗上，停止按钮看起来毫无反应。
 * R106: 文本确认与富确认谁在 pending 就取消谁（富确认 resolve(null) = 拒绝）。
 */
export function cancelConfirmDialog(): boolean {
  if (!pending) return false;
  const req = pending;
  pending = null;
  if (req.kind === "text") req.resolve(false);
  else req.resolve(null);
  listeners.forEach((l) => l(null));
  return true;
}

export interface DecisionParts {
  /** CARD / spec 标题。 */
  cardTitle: string;
  /** CARD 文本（风险摘要/说明）。 */
  riskText: string;
  /** 完整参数纯 JSON 文本（由宿主自渲染，不进 spec、不受 A2uiCard 的 clip 上限）。 */
  argsText: string;
  /** 宿主参数块的小标题。 */
  argsLabel: string;
  /** spec 构造失败时的纯文本确认文案。 */
  fallbackMessage: string;
}

/**
 * R106 必修项1: 构造 a2ui 富决策 spec（只含 CARD + BUTTON_GROUP）。
 * 完整参数不进 spec——宿主在弹窗里自行渲染全文，避免 TEXT_DISPLAY 的 1000 字
 * clip 把危险操作的参数悄悄截断。返回 null 表示构造/校验失败——调用方据此回落
 * 纯文本 appConfirm（R106 fallback）。
 */
export function buildDecisionSpec(parts: DecisionParts): string | null {
  try {
    const clip = (value: string, max: number) =>
      value.length > max ? `${value.slice(0, max)}…` : value;
    // R142 交互重构：spec 只含风险摘要 CARD（展示）。确认/取消按钮由 ConfirmDialog
    // 宿主在底部直接渲染（单步点击，标准 dialog 交互）——不再经 A2uiCard 的
    // BUTTON_GROUP「选中+提交」两步流（用户反馈：提交按钮多余、按钮不在底部）。
    const spec = {
      title: parts.cardTitle,
      fields: [
        { type: "CARD", title: parts.cardTitle, text: clip(parts.riskText, 950) },
      ],
    };
    const text = JSON.stringify(spec);
    const parsed: unknown = JSON.parse(text);
    const fields = (parsed as { fields?: unknown }).fields;
    if (!Array.isArray(fields) || fields.length === 0) return null;
    return text;
  } catch {
    return null;
  }
}

export interface PromptRequest {
  message: string;
  defaultValue: string;
  resolve: (value: string | null) => void;
}

let pendingPrompt: PromptRequest | null = null;
const promptListeners = new Set<(r: PromptRequest | null) => void>();

/** App-wide window.prompt replacement: single-line input dialog; null = 取消。 */
export function appPrompt(message: string, defaultValue = ""): Promise<string | null> {
  return new Promise((resolve) => {
    pendingPrompt?.resolve(null); // auto-cancel any earlier unresolved dialog
    pendingPrompt = { message, defaultValue, resolve };
    promptListeners.forEach((l) => l(pendingPrompt));
  });
}

/** 单行输入弹窗：Enter 保存，Esc 取消；复用 confirm 的视觉与退场动画。 */
export function PromptDialog() {
  const { t } = useI18n();
  const [req, setReq] = useState<PromptRequest | null>(null);
  const [value, setValue] = useState("");
  const [closing, setClosing] = useState(false);
  const exitRef = useRef<number | null>(null);
  useEffect(() => {
    const update = (r: PromptRequest | null) => {
      if (exitRef.current !== null) {
        window.clearTimeout(exitRef.current);
        exitRef.current = null;
      }
      setClosing(false);
      setValue(r?.defaultValue ?? "");
      setReq(r);
    };
    promptListeners.add(update);
    return () => {
      promptListeners.delete(update);
      if (exitRef.current !== null) window.clearTimeout(exitRef.current);
    };
  }, []);
  if (!req) return null;
  const done = (ok: boolean) => {
    if (closing) return;
    req.resolve(ok ? value.trim() : null);
    setClosing(true);
    exitRef.current = window.setTimeout(() => {
      exitRef.current = null;
      setClosing(false);
      setReq(null);
    }, 170);
  };
  return (
    <div className={`confirm-overlay${closing ? " closing" : ""}`} role="dialog" aria-modal="true" onClick={() => done(false)}>
      <div className="confirm-card" onClick={(e) => e.stopPropagation()}>
        <p className="confirm-msg">{req.message}</p>
        <input
          className="confirm-input"
          value={value}
          autoFocus
          maxLength={80}
          onChange={(e) => setValue(e.target.value)}
          onFocus={(e) => e.currentTarget.select()}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              done(true);
            } else if (e.key === "Escape") {
              e.preventDefault();
              done(false);
            }
          }}
        />
        <div className="confirm-actions">
          <button type="button" className="confirm-cancel" onClick={() => done(false)}>{t("cancel")}</button>
          <button type="button" className="confirm-ok" onClick={() => done(true)} disabled={!value.trim()}>{t("save")}</button>
        </div>
      </div>
    </div>
  );
}

export function ConfirmDialog() {
  const { t } = useI18n();
  const [req, setReq] = useState<Pending | null>(null);
  const [closing, setClosing] = useState(false);
  const exitRef = useRef<number | null>(null);
  // 同步防重：提交/取消只结算一次（退场动画期间仍可能收到点击）。
  const settledRef = useRef(false);
  useEffect(() => {
    const update = (r: Pending | null) => {
      // 新的确认请求立刻接管，取消上一轮的退场定时器。
      if (exitRef.current !== null) {
        window.clearTimeout(exitRef.current);
        exitRef.current = null;
      }
      settledRef.current = false;
      setClosing(false);
      setReq(r);
    };
    listeners.add(update);
    return () => {
      listeners.delete(update);
      if (exitRef.current !== null) window.clearTimeout(exitRef.current);
    };
  }, []);
  if (!req) return null;

  // 先播退场再卸载，避免弹窗瞬间消失。
  const startClose = () => {
    setClosing(true);
    exitRef.current = window.setTimeout(() => {
      exitRef.current = null;
      setClosing(false);
      setReq(null);
    }, 170);
  };

  if (req.kind === "rich") {
    const finishRich = (payload: Record<string, unknown> | null) => {
      if (settledRef.current) return;
      settledRef.current = true;
      req.resolve(payload);
      startClose();
    };
    return (
      <div
        className={`confirm-overlay${closing ? " closing" : ""}`}
        role="dialog"
        aria-modal="true"
        onClick={() => finishRich(null)}
      >
        <div className="confirm-card confirm-card-rich" onClick={(e) => e.stopPropagation()}>
          <A2uiCard
            key={req.id}
            code={req.specText}
          />
          {/* R106 必修项1: 宿主自渲染完整参数全文，不进 spec、不 clip，只限高度滚动。 */}
          <div className="confirm-args-label">{req.argsLabel}</div>
          <pre className="confirm-args">{req.argsText}</pre>
          {/* R142：确认/取消按钮固定底部、单步点击直接决策——不再走 A2uiCard 的
              BUTTON_GROUP「选中+提交」两步流（用户反馈：提交按钮多余、效率低）。 */}
          <div className="confirm-actions">
            <button type="button" className="confirm-cancel" onClick={() => finishRich(null)}>
              {t("cancel")}
            </button>
            <button type="button" className="confirm-ok" onClick={() => finishRich({ decision: "confirm" })}>
              {t("confirmOk")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  const done = (ok: boolean) => {
    if (settledRef.current) return;
    settledRef.current = true;
    req.resolve(ok);
    startClose();
  };
  return (
    <div className={`confirm-overlay${closing ? " closing" : ""}`} role="dialog" aria-modal="true" onClick={() => done(false)}>
      <div className="confirm-card" onClick={(e) => e.stopPropagation()}>
        <p className="confirm-msg">{req.message}</p>
        <div className="confirm-actions">
          <button type="button" className="confirm-cancel" onClick={() => done(false)}>{t("cancel")}</button>
          <button type="button" className="confirm-ok" onClick={() => done(true)}>{t("confirmOk")}</button>
        </div>
      </div>
    </div>
  );
}

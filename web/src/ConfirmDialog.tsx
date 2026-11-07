import { useEffect, useRef, useState } from "react";
import { useI18n } from "./i18n";

export interface ConfirmRequest {
  message: string;
  resolve: (ok: boolean) => void;
}

let pending: ConfirmRequest | null = null;
const listeners = new Set<(r: ConfirmRequest | null) => void>();

/** App-wide window.confirm replacement: custom dialog, no WebView URL banner. */
export function appConfirm(message: string): Promise<boolean> {
  return new Promise((resolve) => {
    pending?.resolve(false); // auto-cancel any earlier unresolved dialog
    pending = { message, resolve };
    listeners.forEach((l) => l(pending));
  });
}

/**
 * 关闭当前确认弹窗并按「拒绝」处理。用户点「停止」时调用：
 * 否则工具会一直卡在确认弹窗上，停止按钮看起来毫无反应。
 */
export function cancelConfirmDialog(): boolean {
  if (!pending) return false;
  const req = pending;
  pending = null;
  req.resolve(false);
  listeners.forEach((l) => l(null));
  return true;
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
  const [req, setReq] = useState<ConfirmRequest | null>(null);
  const [closing, setClosing] = useState(false);
  const exitRef = useRef<number | null>(null);
  useEffect(() => {
    const update = (r: ConfirmRequest | null) => {
      // 新的确认请求立刻接管，取消上一轮的退场定时器。
      if (exitRef.current !== null) {
        window.clearTimeout(exitRef.current);
        exitRef.current = null;
      }
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
  const done = (ok: boolean) => {
    if (closing) return;
    req.resolve(ok);
    // 先播退场再卸载，避免弹窗瞬间消失。
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
        <div className="confirm-actions">
          <button type="button" className="confirm-cancel" onClick={() => done(false)}>{t("cancel")}</button>
          <button type="button" className="confirm-ok" onClick={() => done(true)}>{t("confirmOk")}</button>
        </div>
      </div>
    </div>
  );
}

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

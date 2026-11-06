import { useEffect, useState } from "react";
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

export function ConfirmDialog() {
  const { t } = useI18n();
  const [req, setReq] = useState<ConfirmRequest | null>(null);
  useEffect(() => {
    const update = (r: ConfirmRequest | null) => setReq(r);
    listeners.add(update);
    return () => { listeners.delete(update); };
  }, []);
  if (!req) return null;
  const done = (ok: boolean) => {
    req.resolve(ok);
    setReq(null);
  };
  return (
    <div className="confirm-overlay" role="dialog" aria-modal="true" onClick={() => done(false)}>
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

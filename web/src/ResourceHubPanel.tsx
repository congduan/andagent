import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { resourceHubClose, resourceHubList, type ResourceItem } from "./bridge";
import { appConfirm } from "./ConfirmDialog";
import { useI18n } from "./i18n";
import { showResult } from "./toast";

const kinds = ["ssh", "adb", "saf", "embedded", "ssh_transfer", "scrcpy", "cron"] as const;
const labels = {
  ssh: "resourceSsh", adb: "resourceAdb", saf: "resourceSaf",
  embedded: "resourceEmbedded", ssh_transfer: "resourceTransfer",
  scrcpy: "resourceScrcpy", cron: "resourceCron",
} as const;

export function ResourceHubPanel({ onClose, anchor }: { onClose: () => void; anchor: { x: number; y: number } | null }) {
  const { t } = useI18n();
  // R110-review 必修项①：resourceHubList/Close 改为 async（取主帧凭证后再调桥）。
  // 初次渲染先用空态，加载完成后再填充；alive 标志防卸载后 setState。
  const [items, setItems] = useState<ResourceItem[]>([]);
  const [small, setSmall] = useState(false);
  const [now, setNow] = useState(Date.now());
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const refresh = useCallback(async () => {
    const next = await resourceHubList();
    if (!alive.current) return;
    setItems(next);
    setNow(Date.now());
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const age = (since?: number | null) => {
    if (!since) return "";
    const seconds = Math.max(0, Math.floor((now - since) / 1000));
    const time = seconds < 60 ? `${seconds}s` : seconds < 3600 ? `${Math.floor(seconds / 60)}m` : `${Math.floor(seconds / 3600)}h`;
    return t("resourceAge", { time });
  };
  const close = async (item: ResourceItem) => {
    if (!(await appConfirm(t("resourceConfirm", { name: item.title })))) return;
    showResult(await resourceHubClose(item.kind, item.id));
    await refresh();
  };
  const hideEmbedded = async () => {
    showResult(await resourceHubClose("embedded", "browser:hide"));
    await refresh();
  };
  return createPortal(
    <>
      <div className="copy-backdrop" onPointerDown={onClose} />
      <section
        className="resource-hub resource-hub-popover"
        aria-label={t("resourceHub")}
        style={{
          // 水平居中屏幕（用户指定）；top 保持在锚点按钮下方并钳制屏内。
          left: Math.max(8, (window.innerWidth - Math.min(window.innerWidth * 0.92, 480)) / 2),
          top: Math.min(anchor?.y ?? 8, Math.max(8, window.innerHeight * 0.28)),
          maxHeight: window.innerHeight - (anchor?.y ?? 8) - 24,
        }}
        onPointerDown={(event) => event.stopPropagation()}
      >
    <header className="resource-hub-head">
      <strong>🧩 {t("resourceHub")}</strong>
      <div>
        <button type="button" onClick={onClose} aria-label={t("close")}>✕</button>
      </div>
    </header>
    <div className="resource-hub-body">
      {items.length === 0 && <div className="resource-hub-empty"><span className="resource-hub-empty-icon" aria-hidden="true">🔌</span><strong>{t("resourceEmpty")}</strong><small>{t("resourceEmptyHint")}</small></div>}
      {kinds.map(kind => {
        const group = items.filter(item => item.kind === kind);
        if (!group.length) return null;
        return <section key={kind} className="resource-hub-group">
          <h3>{t(labels[kind])} <span>{group.length}</span></h3>
          {group.map(item => <div className="resource-hub-item" key={`${item.kind}:${item.id}`}>
            <div><strong>{item.title}</strong><small>{item.subtitle}</small>{item.aliveSince && <small>{age(item.aliveSince)}</small>}</div>
            {item.canClose ? <div className="resource-hub-actions">
              {kind === "embedded" && <button type="button" onClick={() => { void hideEmbedded(); }}>{t("resourceHide")}</button>}
              <button type="button" onClick={() => void close(item)}>{kind === "embedded" ? t("resourceDestroy") : kind === "cron" ? t("resourcePause") : t("resourceClose")}</button>
            </div> : <small>{t("resourceReadonly")}</small>}
          </div>)}
        </section>;
      })}
    </div>
      </section>
    </>,
    document.body,
  );
}

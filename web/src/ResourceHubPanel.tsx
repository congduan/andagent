import { useEffect, useState } from "react";
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

export function ResourceHubPanel({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const [items, setItems] = useState<ResourceItem[]>(() => resourceHubList());
  const [small, setSmall] = useState(false);
  const [now, setNow] = useState(Date.now());
  const refresh = () => { setItems(resourceHubList()); setNow(Date.now()); };
  useEffect(() => {
    const timer = window.setInterval(refresh, 3000);
    return () => window.clearInterval(timer);
  }, []);
  const age = (since?: number | null) => {
    if (!since) return "";
    const seconds = Math.max(0, Math.floor((now - since) / 1000));
    const time = seconds < 60 ? `${seconds}s` : seconds < 3600 ? `${Math.floor(seconds / 60)}m` : `${Math.floor(seconds / 3600)}h`;
    return t("resourceAge", { time });
  };
  const close = async (item: ResourceItem) => {
    if (!(await appConfirm(t("resourceConfirm", { name: item.title })))) return;
    showResult(resourceHubClose(item.kind, item.id));
    refresh();
  };
  return <section className={`resource-hub ${small ? "resource-hub-small" : ""}`} aria-label={t("resourceHub")}>
    <header className="resource-hub-head">
      <strong>🧩 {t("resourceHub")}</strong>
      <div>
        <button type="button" onClick={() => setSmall(!small)} aria-label={small ? t("resourceFull") : t("resourceSmall")}>{small ? "▢" : "↘"}</button>
        <button type="button" onClick={onClose} aria-label={t("close")}>✕</button>
      </div>
    </header>
    <div className="resource-hub-body">
      {items.length === 0 && <p className="resource-hub-empty">{t("resourceEmpty")}</p>}
      {kinds.map(kind => {
        const group = items.filter(item => item.kind === kind);
        if (!group.length) return null;
        return <section key={kind} className="resource-hub-group">
          <h3>{t(labels[kind])} <span>{group.length}</span></h3>
          {group.map(item => <div className="resource-hub-item" key={`${item.kind}:${item.id}`}>
            <div><strong>{item.title}</strong><small>{item.subtitle}</small>{item.aliveSince && <small>{age(item.aliveSince)}</small>}</div>
            {item.canClose ? <div className="resource-hub-actions">
              {kind === "embedded" && <button type="button" onClick={() => { showResult(resourceHubClose("embedded", "browser:hide")); refresh(); }}>{t("resourceHide")}</button>}
              <button type="button" onClick={() => void close(item)}>{kind === "embedded" ? t("resourceDestroy") : kind === "cron" ? t("resourcePause") : t("resourceClose")}</button>
            </div> : <small>{t("resourceReadonly")}</small>}
          </div>)}
        </section>;
      })}
    </div>
  </section>;
}

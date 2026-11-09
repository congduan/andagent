import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { resourceHubClose, resourceHubSnapshot, type ResourceItem } from "./bridge";
import { appConfirm } from "./ConfirmDialog";
import { Icon, type IconName } from "./icons";
import { useI18n } from "./i18n";
import { showResult } from "./toast";

const kinds = ["ssh", "adb", "saf", "embedded", "ssh_transfer", "scrcpy", "cron"] as const;
type Kind = (typeof kinds)[number];
const labels = {
  ssh: "resourceSsh", adb: "resourceAdb", saf: "resourceSaf",
  embedded: "resourceEmbedded", ssh_transfer: "resourceTransfer",
  scrcpy: "resourceScrcpy", cron: "resourceCron",
} as const;
/** 每类资源一个线性图标，与顶栏其它图标同一套笔触（不用 emoji）。 */
const kindIcons: Record<Kind, IconName> = {
  ssh: "key", adb: "smartphone", saf: "folder", embedded: "globe",
  ssh_transfer: "upload", scrcpy: "monitor", cron: "clock",
};

export function ResourceHubPanel({ onClose, anchor }: { onClose: () => void; anchor: { x: number; y: number } | null }) {
  const { t } = useI18n();
  // R110-review 必修项①：resourceHubList/Close 改为 async（取主帧凭证后再调桥）。
  // 初次渲染先用空态，加载完成后再填充；alive 标志防卸载后 setState。
  const [items, setItems] = useState<ResourceItem[]>([]);
  // 读取失败（凭证未注入 / 桥异常 / 原生返回 ERROR）与“没有资源”必须区分：
  // 此前失败被当成空数组，面板会谎报「当前无存活的连接与任务」。
  const [failed, setFailed] = useState(false);
  const [reason, setReason] = useState<string | undefined>();
  const [now, setNow] = useState(Date.now());
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const refresh = useCallback(async () => {
    const snapshot = await resourceHubSnapshot();
    if (!alive.current) return;
    setItems(snapshot.items);
    setFailed(snapshot.failed);
    setReason(snapshot.reason);
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

  // 浮层贴着顶栏按钮右下方：anchor.x 是按钮右边缘，anchor.y 是按钮下沿。
  // 宽度钳制在屏内，right 也钳制，保证窄屏下不溢出左右边界。
  const viewportW = window.innerWidth;
  const viewportH = window.innerHeight;
  const width = Math.min(viewportW - 16, 420);
  const anchorRight = anchor ? anchor.x : viewportW - 8;
  const right = Math.min(Math.max(8, viewportW - anchorRight), viewportW - width - 8);
  const top = Math.max(8, Math.min(anchor ? anchor.y : 8, Math.max(8, viewportH - 160)));

  return createPortal(
    <>
      <div className="copy-backdrop" onPointerDown={onClose} />
      <section
        className="resource-hub resource-hub-popover"
        aria-label={t("resourceHub")}
        style={{ right, top, width, maxHeight: Math.max(160, viewportH - top - 12) }}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <header className="resource-hub-head">
          <strong className="resource-hub-title">
            <Icon name="puzzle" size={15} />
            {t("resourceHub")}
            {items.length > 0 && <span className="resource-hub-count">{items.length}</span>}
          </strong>
          <button type="button" className="resource-hub-close" onClick={onClose} aria-label={t("close")}>
            <Icon name="x" size={15} />
          </button>
        </header>
        <div className="resource-hub-body">
          {items.length === 0 && (
            <div className="resource-hub-empty">
              <span className="resource-hub-empty-icon" aria-hidden="true"><Icon name={failed ? "refresh" : "plug"} size={20} /></span>
              <strong>{t(failed ? "resourceLoadFailed" : "resourceEmpty")}</strong>
              {failed
                ? <><small className="resource-hub-reason">{reason ?? t("resourceReasonUnknown")}</small><button type="button" onClick={() => { void refresh(); }}>{t("resourceRetry")}</button></>
                : <small>{t("resourceEmptyHint")}</small>}
            </div>
          )}
          {kinds.map(kind => {
            const group = items.filter(item => item.kind === kind);
            if (!group.length) return null;
            return <section key={kind} className="resource-hub-group">
              <h3 className="resource-hub-group-head">
                <Icon name={kindIcons[kind]} size={12} />
                <span className="resource-hub-group-label">{t(labels[kind])}</span>
                <span className="resource-hub-count">{group.length}</span>
              </h3>
              {group.map(item => <div className="resource-hub-item" key={`${item.kind}:${item.id}`}>
                <span className="resource-hub-icon" aria-hidden="true"><Icon name={kindIcons[kind]} size={15} /></span>
                <div className="resource-hub-main">
                  <strong title={item.title}>{item.title}</strong>
                  {(item.subtitle || item.aliveSince) && (
                    <span className="resource-hub-meta">
                      {item.subtitle && <span className="resource-hub-subtitle">{item.subtitle}</span>}
                      {item.subtitle && item.aliveSince ? <i className="resource-hub-dot" aria-hidden="true" /> : null}
                      {item.aliveSince ? <small>{age(item.aliveSince)}</small> : null}
                    </span>
                  )}
                </div>
                {item.canClose ? <div className="resource-hub-actions">
                  {kind === "embedded" && <button type="button" onClick={() => { void hideEmbedded(); }}>{t("resourceHide")}</button>}
                  <button type="button" className="danger" onClick={() => void close(item)}>{kind === "embedded" ? t("resourceDestroy") : kind === "cron" ? t("resourcePause") : t("resourceClose")}</button>
                </div> : <span className="resource-hub-tag">{t("resourceReadonly")}</span>}
              </div>)}
            </section>;
          })}
        </div>
      </section>
    </>,
    document.body,
  );
}

import { useCallback, useEffect, useRef, useState } from "react";
import { useI18n } from "./i18n";

/** 一条用户输入：id 与会话消息 id 一致，text 用于列表预览。 */
export interface MessageNavItem {
  id: string;
  text: string;
}

/** 会话里的消息节点由 App 的 UserMessage 挂上 `msg-<id>`。 */
function messageElement(id: string): HTMLElement | null {
  return document.getElementById(`msg-${id}`);
}

/** 跳转后闪一下高亮，避免"滚过去了但不知道是哪条"。 */
const flashTimers = new WeakMap<HTMLElement, number>();
function flash(el: HTMLElement): void {
  const pending = flashTimers.get(el);
  if (pending) window.clearTimeout(pending);
  el.classList.remove("msg-flash");
  // 强制回流：连续两次跳到同一条也能重播动画。
  void el.offsetWidth;
  el.classList.add("msg-flash");
  flashTimers.set(
    el,
    window.setTimeout(() => {
      el.classList.remove("msg-flash");
      flashTimers.delete(el);
    }, 1400),
  );
}

/** 列表预览：折叠换行与连续空白，标题只取一行。 */
function preview(text: string, max = 120): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

/**
 * 对话记录快捷导航：贴在对话区右下角，点开即可看到本次会话里输入过的
 * 每一条消息，点哪条就滚到哪条并高亮。
 */
export function MessageNav({ items }: { items: MessageNavItem[] }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState("");
  const listRef = useRef<HTMLDivElement | null>(null);

  const close = useCallback(() => setOpen(false), []);
  // 只有"刚打开"时才把当前条目滚进列表中间；后续滚动不打断用户。
  const openedRef = useRef(false);

  // 打开时跟随滚动位置标记"当前读到哪条"，并把它滚进列表可视区。
  useEffect(() => {
    if (!open) {
      openedRef.current = false;
      return;
    }
    const first = !openedRef.current;
    openedRef.current = true;
    const viewport = document.querySelector<HTMLElement>(".viewport");

    const centerRow = (id: string) => {
      const list = listRef.current;
      if (!list || !id) return;
      const row = Array.from(list.children).find(
        (el) => (el as HTMLElement).dataset.navId === id,
      ) as HTMLElement | undefined;
      if (!row) return;
      list.scrollTop = Math.max(0, row.offsetTop - list.clientHeight / 2 + row.offsetHeight / 2);
    };

    const sync = (center = false) => {
      const top = (viewport?.getBoundingClientRect().top ?? 0) + 48;
      let current = items[0]?.id ?? "";
      for (const item of items) {
        const el = messageElement(item.id);
        if (el && el.getBoundingClientRect().top <= top) current = item.id;
      }
      setActive(current);
      if (center) centerRow(current);
    };

    const onScroll = () => sync();

    sync(first);
    viewport?.addEventListener("scroll", onScroll, { passive: true });
    return () => viewport?.removeEventListener("scroll", onScroll);
  }, [open, items]);

  // Esc 收起；不在输入框里抢键。
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const jump = (item: MessageNavItem) => {
    setOpen(false);
    const el = messageElement(item.id);
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "start" });
    flash(el);
  };

  if (items.length === 0) return null;

  return (
    <>
      {open && <div className="msg-nav-scrim" onClick={close} aria-hidden="true" />}
      <div className="msg-nav">
        {open && (
          <div className="msg-nav-panel" role="dialog" aria-label={t("msgNavTitle")}>
            <div className="msg-nav-head">
              <span className="msg-nav-head-title">{t("msgNavTitle")}</span>
              <span className="msg-nav-head-count">{t("msgNavCount", { count: items.length })}</span>
            </div>
            <div className="msg-nav-list" ref={listRef}>
              {items.map((item, index) => (
                <button
                  key={item.id}
                  type="button"
                  data-nav-id={item.id}
                  className={`msg-nav-item ${item.id === active ? "active" : ""}`}
                  aria-label={t("msgNavJump", { index: index + 1 })}
                  onClick={() => jump(item)}
                >
                  <span className="msg-nav-index">{index + 1}</span>
                  <span className="msg-nav-text">{preview(item.text)}</span>
                </button>
              ))}
            </div>
          </div>
        )}
        <button
          type="button"
          className={`msg-nav-btn ${open ? "open" : ""}`}
          aria-expanded={open}
          aria-label={t("msgNavOpen")}
          title={t("msgNavOpen")}
          onClick={() => setOpen((v) => !v)}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none">
            <path
              d="M4.5 7h15M4.5 12h15M4.5 17h9"
              stroke="currentColor"
              strokeWidth="1.9"
              strokeLinecap="round"
            />
          </svg>
          <span className="msg-nav-badge">{items.length}</span>
        </button>
      </div>
    </>
  );
}

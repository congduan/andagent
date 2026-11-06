import { useEffect, useRef, useState } from "react";
import type { SessionMeta } from "./sessions";
import { resolvedTheme, toggleTheme } from "./theme";
import { useI18n } from "./i18n";
import { fmtTokens, type UsageSum } from "./usage";
import { appConfirm } from "./ConfirmDialog";

/* --------------------------- 时间分组折叠 --------------------------- */

const DAY_MS = 86_400_000;
type TimeGroup = "today" | "yesterday" | "week" | "earlier";

const GROUP_ORDER: TimeGroup[] = ["today", "yesterday", "week", "earlier"];

const GROUP_LABEL: Record<TimeGroup, "groupToday" | "groupYesterday" | "groupLast7Days" | "groupEarlier"> = {
  today: "groupToday",
  yesterday: "groupYesterday",
  week: "groupLast7Days",
  earlier: "groupEarlier",
};

function startOfDay(ts: number): number {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** 按更新时间切成 今天 / 昨天 / 近 7 天 / 更早；输入已按时间倒序，组内顺序不变。 */
export function groupSessionsByTime(
  sessions: SessionMeta[],
  now: number = Date.now(),
): { key: TimeGroup; items: SessionMeta[] }[] {
  const today = startOfDay(now);
  const buckets = new Map<TimeGroup, SessionMeta[]>();
  for (const s of sessions) {
    const key: TimeGroup =
      s.updatedAt >= today
        ? "today"
        : s.updatedAt >= today - DAY_MS
          ? "yesterday"
          : s.updatedAt >= today - 7 * DAY_MS
            ? "week"
            : "earlier";
    const list = buckets.get(key);
    if (list) list.push(s);
    else buckets.set(key, [s]);
  }
  return GROUP_ORDER.filter((k) => buckets.has(k)).map((k) => ({ key: k, items: buckets.get(k)! }));
}

/** ↑in ↓out 小字；会话头部与条目共用。 */
function UsageTag({ input, output }: { input: number; output: number }) {
  const { t } = useI18n();
  if (input <= 0 && output <= 0) return null;
  return (
    <span className="usage-tag" title={`${t("input")} ${input} · ${t("output")} ${output} tokens`}>
      ↑{fmtTokens(input)} ↓{fmtTokens(output)}
    </span>
  );
}

/** 单条会话：标题（可改名）+ 时间/token + 重命名/导出/删除图标按钮。 */
function SessionRow({
  session,
  active,
  onClose,
  onSwitch,
  onRename,
  onDelete,
  onExport,
  managing,
  selected,
  onToggle,
}: {
  session: SessionMeta;
  active: boolean;
  onClose: () => void;
  onSwitch: (id: string) => void;
  onRename: (id: string, title: string) => void;
  onDelete: (id: string) => void;
  onExport: (id: string) => void;
  managing: boolean;
  selected: boolean;
  onToggle: (id: string) => void;
}) {
  const { t, language } = useI18n();
  const s = session;
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(s.title);
  // Esc 取消后 blur 仍会触发，用它避免把旧草稿写回去。
  const cancelRef = useRef(false);

  const startEdit = () => {
    cancelRef.current = false;
    setDraft(s.title);
    setEditing(true);
  };

  const commit = () => {
    const next = draft.trim();
    if (next && next !== s.title) onRename(s.id, next);
    setEditing(false);
  };

  const cancel = () => {
    cancelRef.current = true;
    setEditing(false);
  };

  return (
    <div
      className={`drawer-item ${!managing && active ? "active" : ""} ${selected ? "selected" : ""} ${managing ? "managing" : ""}`}
      onClick={() => {
        if (editing) return;
        if (managing) { onToggle(s.id); return; }
        onSwitch(s.id);
        onClose();
      }}
      role={managing ? "checkbox" : "button"}
      aria-checked={managing ? selected : undefined}
      aria-label={managing ? s.title : undefined}
      tabIndex={0}
      onKeyDown={(e) => {
        if (editing) return;
        if (e.key === "Enter" || (managing && e.key === " ")) {
          e.preventDefault();
          if (managing) { onToggle(s.id); return; }
          onSwitch(s.id);
          onClose();
        }
      }}
    >
      {managing && <span className="drawer-item-check" aria-hidden="true">{selected ? "✓" : ""}</span>}
      {editing ? (
        <input
          className="drawer-item-rename"
          value={draft}
          autoFocus
          maxLength={80}
          aria-label={t("renameSession", { name: s.title })}
          onChange={(e) => setDraft(e.target.value)}
          onClick={(e) => e.stopPropagation()}
          onFocus={(e) => e.currentTarget.select()}
          onBlur={() => {
            if (cancelRef.current) {
              cancelRef.current = false;
              setEditing(false);
              return;
            }
            commit();
          }}
          onKeyDown={(e) => {
            e.stopPropagation();
            if (e.key === "Enter") {
              e.preventDefault();
              commit();
            } else if (e.key === "Escape") {
              e.preventDefault();
              cancel();
            }
          }}
        />
      ) : (
        <span className="drawer-item-title">{s.title}</span>
      )}
      <span className="drawer-item-sub">
        <span className="drawer-item-meta">
          <span className="drawer-item-time">
            {new Date(s.updatedAt).toLocaleString(language === "zh" ? "zh-CN" : "en-US", {
              month: "numeric",
              day: "numeric",
              hour: "2-digit",
              minute: "2-digit",
            })}
          </span>
          {s.usage && (
            <span className="drawer-item-usage">
              <UsageTag input={s.usage.input} output={s.usage.output} />
            </span>
          )}
        </span>
        {!managing && <span className="drawer-item-actions">
          <button
            type="button"
            className="drawer-action"
            aria-label={t("renameSession", { name: s.title })}
            title={t("rename")}
            onClick={(e) => { e.stopPropagation(); startEdit(); }}
          >
            <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none">
              <path
                d="M4.5 19.5l.9-3.6L16 5.3a2 2 0 112.8 2.8L8.1 18.6l-3.6.9z"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <button
            type="button"
            className="drawer-action"
            aria-label={t("exportSession", { name: s.title })}
            title={t("export")}
            onClick={(e) => { e.stopPropagation(); onExport(s.id); }}
          >
            <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none">
              <path
                d="M12 4v11m0 0l-4-4m4 4l4-4M5 19h14"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <button
            type="button"
            className="drawer-action drawer-action-danger"
            aria-label={t("deleteSession", { name: s.title })}
            title={t("delete")}
            onClick={async (e) => {
              e.stopPropagation();
              if (await appConfirm(t("confirmDeleteSession", { name: s.title }))) onDelete(s.id);
            }}
          >
            <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none">
              <path
                d="M4.5 7h15M9.5 4.5h5M6.5 7l.9 12a1.5 1.5 0 001.5 1.4h6.2a1.5 1.5 0 001.5-1.4L17.5 7M10 11v6m4-6v6"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
        </span>}
      </span>
    </div>
  );
}

/**
 * Android-style navigation drawer: slides in from the left, scrim backdrop,
 * swipe-to-close. Lists conversations (new / switch / delete) and shows the
 * app version at the bottom.
 */
export function Drawer({
  open,
  sessions,
  currentId,
  version,
  sessionUsage,
  onClose,
  onNew,
  onSwitch,
  onRename,
  onDelete,
  onExport,
  onDeleteMany,
  onExportMany,
  onSettings,
}: {
  open: boolean;
  sessions: SessionMeta[];
  currentId: string;
  version: string;
  sessionUsage: UsageSum;
  onClose: () => void;
  onNew: () => void;
  onSwitch: (id: string) => void;
  onRename: (id: string, title: string) => void;
  onDelete: (id: string) => void;
  onExport: (id: string) => void;
  onDeleteMany: (ids: string[]) => void;
  onExportMany: (ids: string[]) => void;
  onSettings: () => void;
}) {
  const { t } = useI18n();
  const [visible, setVisible] = useState(open);
  const [theme, setThemeState] = useState(() => resolvedTheme());
  // 分组折叠状态：默认全部展开，按分组 key 记录。
  const [collapsed, setCollapsed] = useState<Partial<Record<TimeGroup, boolean>>>({});
  const [managing, setManaging] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const visibleIds = groupSessionsByTime(sessions)
    .filter(({ key }) => !collapsed[key])
    .flatMap(({ items }) => items.map((session) => session.id));
  const selectedVisible = visibleIds.filter((id) => selectedIds.has(id));
  const allSelected = visibleIds.length > 0 && selectedVisible.length === visibleIds.length;
  const toggleSelected = (id: string) => setSelectedIds((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  // Re-render the sun/moon shortcut when the theme changes.
  useEffect(() => {
    const listener = () => setThemeState(resolvedTheme());
    window.addEventListener("andagent:themechange", listener);
    return () => window.removeEventListener("andagent:themechange", listener);
  }, []);

  // Keep the element mounted during the close transition.
  useEffect(() => {
    if (open) setVisible(true);
    else {
      setManaging(false);
      setSelectedIds(new Set());
      const t = setTimeout(() => setVisible(false), 220);
      return () => clearTimeout(t);
    }
  }, [open]);

  if (!visible) return null;

  return (
    <div className={`drawer-root ${open ? "open" : ""}`}>
      <div className="drawer-scrim" onClick={onClose} aria-hidden="true" />
      <nav className="drawer" aria-label={t("sessions")}>
        <div className="drawer-head">
          <div>
            <strong>AndAgent</strong>
            <div className="drawer-sub">{t("sessionsShort")} · v{version}</div>
          </div>
          <div className="drawer-head-right">
            <UsageTag input={sessionUsage.input} output={sessionUsage.output} />
            <button
              type="button"
              className="icon-btn"
              aria-label={theme === "dark" ? t("switchLight") : t("switchDark")}
              title={theme === "dark" ? t("lightMode") : t("darkMode")}
              onClick={toggleTheme}
            >
              {theme === "dark" ? "☀️" : "🌙"}
            </button>
          </div>
        </div>

        <button type="button" className="drawer-new" onClick={onNew}>
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          {t("newChat")}
        </button>

        <div className="drawer-manage-head">
          <span>{managing ? t("selectedCount", { count: selectedVisible.length }) : t("sessions")}</span>
          {managing && <button type="button" onClick={() => setSelectedIds((prev) => {
            const next = new Set(prev);
            if (allSelected) visibleIds.forEach((id) => next.delete(id));
            else visibleIds.forEach((id) => next.add(id));
            return next;
          })} disabled={visibleIds.length === 0}>{t(allSelected ? "deselectAll" : "selectAll")}</button>}
          <button type="button" onClick={() => { setManaging(!managing); setSelectedIds(new Set()); }}>
            {t(managing ? "exitManage" : "manageSessions")}
          </button>
        </div>

        <div className="drawer-list">
          {sessions.length === 0 && <div className="drawer-empty">{t("noSessions")}</div>}
          {groupSessionsByTime(sessions).map(({ key, items }) => {
            const isCollapsed = collapsed[key] === true;
            return (
              <section className="drawer-group" key={key}>
                <button
                  type="button"
                  className="drawer-group-head"
                  aria-expanded={!isCollapsed}
                  onClick={() => setCollapsed((prev) => ({ ...prev, [key]: !isCollapsed }))}
                >
                  <svg
                    className="drawer-group-chevron"
                    viewBox="0 0 24 24"
                    width="12"
                    height="12"
                    aria-hidden="true"
                    fill="none"
                  >
                    <path d="M9 6l6 6-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <span className="drawer-group-title">{t(GROUP_LABEL[key])}</span>
                  <span className="drawer-group-count">{items.length}</span>
                </button>
                {/* 常驻挂载 + 网格行高过渡：分组折叠/展开是滑动而不是瞬断。 */}
                <div className={`drawer-group-body${isCollapsed ? "" : " open"}`}>
                  <div className="drawer-group-inner">
                    {items.map((s) => (
                      <SessionRow
                        key={s.id}
                        session={s}
                        active={s.id === currentId}
                        onClose={onClose}
                        onSwitch={onSwitch}
                        onRename={onRename}
                        onDelete={onDelete}
                        onExport={onExport}
                        managing={managing}
                        selected={selectedIds.has(s.id)}
                        onToggle={toggleSelected}
                      />
                    ))}
                  </div>
                </div>
              </section>
            );
          })}
        </div>

        {managing && <div className="drawer-batch-actions">
          <button type="button" disabled={selectedVisible.length === 0} onClick={async () => {
            const ids = selectedVisible;
            if (await appConfirm(t("batchDeleteConfirm", { count: ids.length }))) {
              onDeleteMany(ids);
              setSelectedIds(new Set());
            }
          }}>{t("batchDelete")}</button>
          <button type="button" disabled={selectedVisible.length === 0} onClick={() => onExportMany(selectedVisible)}>{t("batchExport")}</button>
        </div>}

        <button
          type="button"
          className="drawer-settings"
          onClick={() => { onClose(); onSettings(); }}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M12 15.2a3.2 3.2 0 100-6.4 3.2 3.2 0 000 6.4z" stroke="currentColor" strokeWidth="1.8" fill="none" />
            <path
              d="M19.4 13a7.8 7.8 0 000-2l2-1.5-2-3.4-2.3 1a7.6 7.6 0 00-1.7-1L15 3.6h-4l-.4 2.5a7.6 7.6 0 00-1.7 1l-2.3-1-2 3.4L6.6 11a7.8 7.8 0 000 2l-2 1.5 2 3.4 2.3-1a7.6 7.6 0 001.7 1l.4 2.5h4l.4-2.5a7.6 7.6 0 001.7-1l2.3 1 2-3.4-2-1.5z"
              stroke="currentColor"
              strokeWidth="1.4"
              fill="none"
              strokeLinejoin="round"
            />
          </svg>
          <span>{t("settings")}</span>
          <span className="drawer-settings-chevron" aria-hidden="true">›</span>
        </button>
        <div className="drawer-foot">AndAgent v{version} · {t("assistantTagline")}</div>
      </nav>
    </div>
  );
}

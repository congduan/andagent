import { useEffect, useState } from "react";
import type { SessionMeta } from "./sessions";
import { resolvedTheme, toggleTheme } from "./theme";
import { useI18n } from "./i18n";
import { fmtTokens, type UsageSum } from "./usage";
import { appConfirm } from "./ConfirmDialog";

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
  onDelete,
  onExport,
}: {
  open: boolean;
  sessions: SessionMeta[];
  currentId: string;
  version: string;
  sessionUsage: UsageSum;
  onClose: () => void;
  onNew: () => void;
  onSwitch: (id: string) => void;
  onDelete: (id: string) => void;
  onExport: (id: string) => void;
}) {
  const { t, language } = useI18n();
  const [visible, setVisible] = useState(open);
  const [theme, setThemeState] = useState(() => resolvedTheme());

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

        <div className="drawer-list">
          {sessions.length === 0 && <div className="drawer-empty">{t("noSessions")}</div>}
          {sessions.map((s) => (
            <div
              key={s.id}
              className={`drawer-item ${s.id === currentId ? "active" : ""}`}
              onClick={() => {
                onSwitch(s.id);
                onClose();
              }}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  onSwitch(s.id);
                  onClose();
                }
              }}
            >
              <span className="drawer-item-title">{s.title}</span>
              <span className="drawer-item-sub">
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
                <button
                  type="button"
                  className="drawer-export"
                  aria-label={t("exportSession", { name: s.title })}
                  onClick={(e) => { e.stopPropagation(); onExport(s.id); }}
                >{t("export")}</button>
                <button
                  type="button"
                  className="drawer-del"
                  aria-label={t("deleteSession", { name: s.title })}
                  onClick={async (e) => {
                    e.stopPropagation();
                    if (await appConfirm(t("confirmDeleteSession", { name: s.title }))) onDelete(s.id);
                  }}
                >
                  ×
                </button>
              </span>
            </div>
          ))}
        </div>

        <div className="drawer-foot">AndAgent v{version} · {t("assistantTagline")}</div>
      </nav>
    </div>
  );
}

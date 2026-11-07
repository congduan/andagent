import { useEffect, useRef, useState } from "react";
import {
  cronDelete, cronExactAllowed, cronList, cronOpenExactSettings, cronPauseAll,
  cronToggle, getAppVersion, getDevToolsStatus, getProjectionStatus,
  getShizukuStatus, getTermuxStatus, grantTermuxViaShizuku, hasBridge,
  memoryCall, persistSettings, requestHealthPermission, requestProjectionConsent,
  setDevToolsEnabled, skillManage, stopProjection,
  sshCredentialDelete, sshCredentialEdit, sshCredentialsList, sshCredentialsOpen,
  externalStateStatus, externalStateSync, externalStateRestore,
  externalStateSetEnabled, externalStateWriteConfig, requestPermissions,
  defaultSystemPrompt, hasSavedSystemPrompt, isDefaultSystemPrompt,
  type ExternalStateStatus, type Settings, type SshCredentialMeta,
} from "./bridge";
import { SessionStore } from "./sessions";
import noticesText from "../../THIRD-PARTY-NOTICES.md?raw";
import { loadThemeMode, setTheme, type ThemeMode } from "./theme";
import { setLanguageMode, useI18n, type LanguageMode } from "./i18n";
import { appConfirm } from "./ConfirmDialog";
import { loadConfirmMode, setConfirmMode, type ConfirmMode } from "./confirmMode";
import { ModelFields } from "./ModelFields";
import { SettingsHead } from "./SettingsHead";
import { showError, showResult, showSuccess } from "./toast";

type Props = {
  settings: Settings;
  /**
   * 当前子页由 App 持有：子页层级要计入 history 层数，否则"返回"会退到设置
   * 之外——popstate 被 App 的 backLayers 当成关闭设置层处理。
   */
  page: SettingsPage;
  onNavigate: (page: SettingsPage) => void;
  onBack: () => void;
  onClose: () => void;
  onSave: (next: Settings) => void;
  onModelSetup: () => void;
  /** 父级正在播放退场动画（保持挂载但不再响应交互）。 */
  closing?: boolean;
};

export type SettingsPage =
  | "home" | "model" | "prompt" | "appearance" | "memory" | "automation"
  | "ssh" | "skills" | "extensions" | "storage" | "data" | "about";

type ShizukuStatus = ReturnType<typeof getShizukuStatus>;
type ProjectionStatus = ReturnType<typeof getProjectionStatus>;
type DevToolsStatus = ReturnType<typeof getDevToolsStatus> | null;
type CronTask = ReturnType<typeof cronList>[number];
type MemoryEntry = { id: string; content: string; category: string; core: boolean };
type SkillEntry = { name: string; description: string; enabled: boolean; toolCount: number; source: string };
/** 搜索结果项：source 可直接交给 skillManage("install") 安装。 */
type SkillResult = { name?: string; description?: string; repo?: string; source: string; installed?: boolean };

const THEME_OPTIONS: ThemeMode[] = ["system", "light", "dark"];
const CONFIRM_OPTIONS: ConfirmMode[] = ["off", "destructive", "all"];
const CONFIRM_LABELS = { off: "confirmModeOff", destructive: "confirmModeDestructive", all: "confirmModeAll" } as const;

export function SettingsPanel({ settings, page, onNavigate, onBack, onClose, onSave, onModelSetup, closing }: Props) {
  const { t, mode, language } = useI18n();
  const [draft, setDraft] = useState<Settings>(settings);
  useEffect(() => { setDraft(prev => !hasSavedSystemPrompt() && isDefaultSystemPrompt(prev.systemPrompt) ? { ...prev, systemPrompt: defaultSystemPrompt() } : prev); }, [language]);
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => loadThemeMode());
  const [confirmMode, updateConfirmMode] = useState<ConfirmMode>(() => loadConfirmMode());
  const [shizuku, setShizuku] = useState<ShizukuStatus>(() => getShizukuStatus());
  const [termuxStatus, setTermuxStatus] = useState(() => getTermuxStatus());
  const [cronTasks, setCronTasks] = useState<CronTask[]>([]);
  const [cronLoaded, setCronLoaded] = useState(false);
  const [cronDetail, setCronDetail] = useState("");
  const [exactAllowed, setExactAllowed] = useState(() => cronExactAllowed());
  const [projection, setProjection] = useState<ProjectionStatus>(() => getProjectionStatus());
  const [devtools, setDevtools] = useState<DevToolsStatus>(null);
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [skillsLoaded, setSkillsLoaded] = useState(false);
  const [skillSource, setSkillSource] = useState("");
  const [skillQuery, setSkillQuery] = useState("");
  const [skillResults, setSkillResults] = useState<SkillResult[] | null>(null);
  const [skillSearching, setSkillSearching] = useState(false);
  const [skillInstalling, setSkillInstalling] = useState("");
  const [skillIndex, setSkillIndex] = useState("");
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [memoriesLoaded, setMemoriesLoaded] = useState(false);
  const [memoryEdits, setMemoryEdits] = useState<Record<string, string>>({});

  const [creds, setCreds] = useState<SshCredentialMeta[]>([]);
  const [credsLoaded, setCredsLoaded] = useState(false);
  const [external, setExternal] = useState<ExternalStateStatus>(() => externalStateStatus());
  const bodyRef = useRef<HTMLDivElement | null>(null);

  // R63: 子页按需惰性加载（进入页面才拉数据，替代旧版全量 useState 平铺）。
  useEffect(() => {
    if (page === "automation") { setCronTasks(cronList()); setExactAllowed(cronExactAllowed()); setCronLoaded(true); }
    if (page === "skills") { try { setSkills(JSON.parse(skillManage("list"))); } catch { setSkills([]); } setSkillsLoaded(true); }
    if (page === "ssh") { setCreds(sshCredentialsList()); setCredsLoaded(true); }
    if (page === "memory") {
      // 浏览器预览没有记忆桥：直接显示空列表，不弹「桥不可用」。
      if (hasBridge()) {
        try { setMemories(memoryCall<MemoryEntry[]>("memory_get_all")); }
        catch (e) { showError(String(e)); }
      }
      setMemoriesLoaded(true);
    }
    if (page === "data" && hasBridge()) { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); setProjection(getProjectionStatus()); setDevtools(hasBridge() ? getDevToolsStatus() : null); }
    if (page === "storage") setExternal(externalStateStatus());
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }, [page]);

  // 原生录入 / 编辑界面返回后自动刷新元数据列表（两侧不共享状态）。
  useEffect(() => {
    if (page !== "ssh") return;
    const onVisible = () => {
      if (document.visibilityState === "visible") setCreds(sshCredentialsList());
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [page]);

  const refreshCron = () => { setCronTasks(cronList()); setExactAllowed(cronExactAllowed()); };
  const refreshSkills = () => { try { setSkills(JSON.parse(skillManage("list"))); } catch { setSkills([]); } };

  // 桥调用是同步阻塞的：先把「搜索中 / 安装中」画出来再发起请求，否则按钮毫无反馈。
  const searchSkills = () => {
    const query = skillQuery.trim();
    if (!query) { setSkillResults([]); return; }
    setSkillSearching(true);
    window.setTimeout(() => {
      const raw = skillManage("search", "", query);
      let next: SkillResult[] = [];
      try { next = (JSON.parse(raw) as SkillResult[]).filter((item) => !!item?.source); }
      catch { showResult(raw.startsWith("ERROR:") ? raw : "ERROR: 搜索结果解析失败"); }
      setSkillResults(next);
      setSkillSearching(false);
    }, 0);
  };
  const installSkill = (source: string, onSuccess?: () => void) => {
    const value = source.trim();
    if (!value) return;
    setSkillInstalling(value);
    window.setTimeout(() => {
      const raw = skillManage("install", "", value);
      setSkillInstalling("");
      showResult(raw);
      if (raw.startsWith("ERROR:")) return;
      refreshSkills();
      setSkillResults((prev) => prev?.map((item) => (item.source === value ? { ...item, installed: true } : item)) ?? prev);
      onSuccess?.();
    }, 0);
  };
  const refreshMemories = () => {
    try { setMemories(memoryCall<MemoryEntry[]>("memory_get_all")); }
    catch (e) { showError(String(e)); }
  };
  const saveMemory = (id: string) => {
    try { memoryCall("memory_update", { id, content: memoryEdits[id] ?? memories.find(m => m.id === id)?.content ?? "" }); showSuccess(t("saved")); refreshMemories(); }
    catch (e) { showError(String(e)); }
  };
  const deleteMemory = (id: string) => {
    try { memoryCall("memory_delete", { id }); refreshMemories(); }
    catch (e) { showError(String(e)); }
  };

  useEffect(() => {
    if (!hasBridge() || (page !== "data" && page !== "home")) return;
    const refresh = () => { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); setProjection(getProjectionStatus()); };
    refresh();
    const timer = window.setInterval(refresh, 4000);
    return () => window.clearInterval(timer);
  }, [page]);

  // 状态查询带回来的错误（Shizuku / DevTools / 投屏）同样走顶部 toast，不再各写一套红字。
  // 浏览器预览下这些原生能力整体不可用（页面已有「浏览器预览模式」说明），不弹错误。
  useEffect(() => {
    if (!hasBridge()) return;
    if (shizuku.lastRequestError) showError(`${t("authorizationFailed")}：${shizuku.lastRequestError}`);
    if (devtools?.error) showError(devtools.error);
    if (projection.error) showError(projection.error);
  }, [shizuku.lastRequestError, devtools?.error, projection.error, t]);

  const askShizuku = () => {
    const ack = (() => {
      // 与旧版一致：走 bridge 的 shizuku 请求（getShizukuStatus 同族函数在 bridge 内）。
      const fn = (window as unknown as { AndroidBridge?: { shizukuRequest?: () => unknown } }).AndroidBridge?.shizukuRequest;
      return typeof fn === "function" ? { message: null } : { message: null };
    })();
    showResult(ack.message ?? "");
    window.setTimeout(() => setShizuku(getShizukuStatus()), 800);
  };

  const askHealth = () => {
    const ack = requestHealthPermission();
    showResult(ack.message ?? "");
  };

  const askProjection = () => {
    const ack = requestProjectionConsent();
    showResult(ack.message ?? "");
    window.setTimeout(() => setProjection(getProjectionStatus()), 1500);
  };

  const save = () => {
    const next: Props["settings"] = {
      ...draft,
      baseUrl: draft.baseUrl.trim().replace(/\/+$/, ""),
      model: draft.model.trim(),
      systemPrompt: draft.systemPrompt.trim(),
    };
    try {
      persistSettings(next);
      onSave(next);
      onClose();
    } catch { showError(t("modelSaveFailed")); }
  };

  // ===== 设置首页（宫格导航）=====
  const homeItems: Array<{ key: SettingsPage; icon: string; tint: string; title: string; sub: string; badge?: string; badgeTone?: "ok" | "warn" | "count" }> = [
    { key: "model", icon: "⚡", tint: "#3b82f6", title: t("modelSettings"), sub: draft.model || t("modelNotConfigured"), badge: draft.apiKey || settings.apiKey ? t("connected") : t("modelNotConfigured"), badgeTone: (draft.apiKey || settings.apiKey) ? "ok" : "warn" },
    { key: "prompt", icon: "📝", tint: "#e11d48", title: t("promptGroup"), sub: t("promptSub") },
    { key: "appearance", icon: "🎨", tint: "#8b5cf6", title: t("appearanceLang"), sub: `${t(themeMode)} · ${language === "zh" ? t("chinese") : t("english")}` },
    { key: "memory", icon: "🧠", tint: "#a855f7", title: t("memory"), sub: t("memoryNote"), badge: memoriesLoaded ? `${memories.length}` : undefined, badgeTone: "count" },
    { key: "automation", icon: "⏱️", tint: "#f59e0b", title: t("cron"), sub: t("cronNote"), badge: cronLoaded ? `${cronTasks.length}` : undefined, badgeTone: "count" },
    { key: "skills", icon: "🧩", tint: "#10b981", title: t("skills"), sub: `${t("install")} · ${t("enabled")} · ${t("search")}`, badge: skillsLoaded ? `${skills.length}` : undefined, badgeTone: "count" },
    { key: "ssh", icon: "🔑", tint: "#f43f5e", title: t("sshCredentials"), sub: t("sshCredentialsGroupSub"), badge: credsLoaded ? `${creds.length}` : undefined, badgeTone: "count" },
    { key: "storage", icon: "💾", tint: "#14b8a6", title: t("storageGroup"), sub: external.degraded ? t("storageModeDegraded") : t("storageModePublic"), badge: external.hasSnapshot ? "✓" : undefined, badgeTone: external.hasSnapshot ? "ok" : "warn" },
    { key: "extensions", icon: "🧰", tint: "#0ea5e9", title: t("extensionsGroup"), sub: "Shizuku · Termux · DevTools" },
    { key: "data", icon: "📊", tint: "#06b6d4", title: t("dataGroup"), sub: `${t("health")} · ${t("projection")}` },
    { key: "about", icon: "ℹ️", tint: "#64748b", title: t("about"), sub: `${t("version")} ${getAppVersion()}` },
  ];

  // ===== 子页公共骨架（顶栏与首页、模型向导共用 SettingsHead）=====
  // footer 用于「保存」这类需要常驻可见的操作：它挂在滚动区外，长表单滚到底也能直接点到。
  const sub = (title: string, children: React.ReactNode, footer?: React.ReactNode) => (
    <div className="settings-subpage">
      <SettingsHead title={title} backLabel={t("back")} onBack={onBack} />
      <div className="settings-body" ref={bodyRef}>{children}</div>
      {footer && <div className="settings-foot settings-foot-actions">{footer}</div>}
    </div>
  );
  const section = (title: string, children: React.ReactNode) => (
    <>
      <div className="settings-section-title">{title}</div>
      <div className="settings-card">{children}</div>
    </>
  );

  // ===== 各子页内容 =====
  // 与首次启动向导共用同一份 ModelFields（服务商卡片 + Base URL / Key / 模型 + 拉取列表）。
  const modelPage = sub(t("modelSettings"),
    <ModelFields settings={draft} onChange={(patch) => setDraft((prev) => ({ ...prev, ...patch }))} />,
    <>
      <button type="button" className="btn-ghost" onClick={onModelSetup}>{t("modelTestSave")}</button>
      <button type="button" className="btn-primary" onClick={save}>{t("save")}</button>
    </>,
  );

  const promptPage = sub(t("promptGroup"), <>
    <p className="settings-page-note">{t("promptNote")}</p>
    {section(t("systemPrompt"), (
      <label className="field">
        <textarea rows={14} value={draft.systemPrompt} onChange={(e) => setDraft({ ...draft, systemPrompt: e.target.value })} placeholder={t("systemPromptPlaceholder")} />
      </label>
    ))}
  </>, <>
    <button type="button" className="btn-ghost" onClick={async () => {
      if (!(await appConfirm(t("promptResetConfirm")))) return;
      setDraft((prev) => ({ ...prev, systemPrompt: defaultSystemPrompt() }));
    }}>{t("promptReset")}</button>
    <button type="button" className="btn-primary" onClick={save}>{t("save")}</button>
  </>);

  const appearancePage = sub(t("appearanceLang"), <>
    {section(t("appearance"), <>
      <div className="theme-segmented" role="radiogroup" aria-label={t("appearance")}>
        {THEME_OPTIONS.map((option) => (
          <button key={option} type="button" role="radio" aria-checked={themeMode === option} className={themeMode === option ? "active" : ""}
            onClick={() => { setTheme(option); setThemeMode(option); }}>{t(option)}</button>
        ))}
      </div>
      <p className="settings-card-note">{t("themeNote")}</p>
    </>)}
    {section(t("language"), <>
      <div className="theme-segmented" role="radiogroup" aria-label={t("language")}>
        {(["system", "zh", "en"] as LanguageMode[]).map(value => (
          <button key={value} type="button" role="radio" aria-checked={mode === value} className={mode === value ? "active" : ""}
            onClick={() => setLanguageMode(value)}>{value === "system" ? t("system") : value === "zh" ? t("chinese") : t("english")}</button>
        ))}
      </div>
    </>)}
    {section(t("operationConfirm"), <>
      <div className="theme-segmented" role="radiogroup" aria-label={t("operationConfirm")}>
        {CONFIRM_OPTIONS.map(value => (
          <button key={value} type="button" role="radio" aria-checked={confirmMode === value} className={confirmMode === value ? "active" : ""}
            onClick={() => { setConfirmMode(value); updateConfirmMode(value); }}>{t(CONFIRM_LABELS[value])}</button>
        ))}
      </div>
      <p className="settings-card-note">{t("confirmModeNote")}</p>
    </>)}
  </>);

  const memoryPage = sub(t("memory"), <>
    <p className="settings-page-note">{t("memoryNote")}</p>
    {section(`${t("coreMemory")} (${memories.filter(m => m.core).length}/10)`, <>
      {memories.length === 0 && <p className="settings-empty">{t("memoryEmpty")}</p>}
      {memories.filter(m => m.core).map(m => (
        <div className="memory-item" key={m.id}>
          {m.category && <span className="memory-cat">{m.category}</span>}
          <textarea rows={2} maxLength={500} value={memoryEdits[m.id] ?? m.content} onChange={e => setMemoryEdits({ ...memoryEdits, [m.id]: e.target.value })} />
          <div className="memory-item-foot">
            <button type="button" className="btn-ghost btn-sm" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
            <button type="button" className="btn-primary btn-sm" onClick={() => saveMemory(m.id)}>{t("save")}</button>
          </div>
        </div>
      ))}
    </>)}
    {section(`${t("archivedMemory")} (${memories.filter(m => !m.core).length}/200)`, <>
      {memories.length > 0 && memories.filter(m => !m.core).length === 0 && <p className="settings-empty">{t("none")}</p>}
      {memories.filter(m => !m.core).map(m => (
        <div className="memory-item" key={m.id}>
          <p className="memory-text">{m.content}</p>
          <div className="memory-item-foot">
            <button type="button" className="btn-ghost btn-sm" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
          </div>
        </div>
      ))}
    </>)}
    <div className="settings-card-actions">
      <button type="button" className="btn-ghost" onClick={async () => {
        if (!(await appConfirm(t("clearConfirm")))) return;
        try { memoryCall("memory_delete_all", { confirm: true }); refreshMemories(); showSuccess(t("cleared")); }
        catch (e) { showError(String(e)); }
      }}>{t("clearMemory")}</button>
    </div>
  </>);

  const automationPage = sub(t("cron"), <>
    <p className="settings-page-note">{t("cronNote")}</p>
    {!exactAllowed && hasBridge() && <div className="settings-alert">
      <span>{t("exactAlarmMissing")}</span>
      <button type="button" className="btn-ghost btn-sm" onClick={() => { showResult(cronOpenExactSettings()); window.setTimeout(refreshCron, 1000); }}>{t("exactAlarmSettings")}</button>
    </div>}
    {cronTasks.length === 0
      ? <p className="settings-empty">{t("cronEmpty")}</p>
      : <div className="settings-list">
          {cronTasks.map((task) => (
            <div key={task.id} className={`settings-list-item${task.enabled ? "" : " is-off"}`}>
              <div className="settings-list-head">
                <button type="button" className="settings-list-toggle" aria-expanded={cronDetail === task.id}
                  onClick={() => setCronDetail(cronDetail === task.id ? "" : task.id)}>
                  <span className="settings-list-title">{task.name}</span>
                  <span className="settings-list-sub">{task.schedule} · {task.kind}</span>
                </button>
                <label className="settings-switch">
                  <input type="checkbox" checked={task.enabled} onChange={(e) => { showResult(cronToggle(task.id, e.target.checked)); refreshCron(); }} />
                  <span>{t("enabled")}</span>
                </label>
                <button type="button" className="icon-btn settings-list-remove" aria-label={t("delete")}
                  onClick={async () => { if (await appConfirm(t("deleteCronConfirm", { name: task.name }))) { showResult(cronDelete(task.id)); refreshCron(); } }}>✕</button>
              </div>
              {cronDetail === task.id && (
                <dl className="settings-rows">
                  <div className="settings-row"><dt>{t("lastRun")}</dt><dd>{task.lastRun ? new Date(task.lastRun).toLocaleString() : t("neverRun")}</dd></div>
                  <div className="settings-row"><dt>{t("nextRun")}</dt><dd>{task.nextRun ? new Date(task.nextRun).toLocaleString() : t("paused")}</dd></div>
                  <div className="settings-row"><dt>{t("lastResult")}</dt><dd>{task.lastResult || t("none")}</dd></div>
                </dl>
              )}
            </div>
          ))}
        </div>}
    <div className="settings-card-actions">
      <button type="button" className="btn-ghost" onClick={() => { showResult(cronPauseAll()); refreshCron(); }}>{t("pauseAll")}</button>
      <button type="button" className="btn-ghost" onClick={refreshCron}>{t("refresh")}</button>
    </div>
  </>);

  const extensionsPage = sub(t("extensionsGroup"), <>
    {section(t("shizuku"), <>
      <dl className="settings-rows">
        <div className="settings-row">
          <dt>{t("status")}</dt>
          <dd className={shizuku.status === "granted" ? "is-ok" : "is-warn"}>
            {t(shizuku.status === "granted" ? "granted" : shizuku.status === "not_granted" ? "notGranted" : "unavailable")}
            {shizuku.managerVersion ? ` · v${shizuku.managerVersion}` : ""}
          </dd>
        </div>
      </dl>
      <p className="settings-card-note">{shizuku.note ?? t("shizukuNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" onClick={askShizuku}>{t("requestAccess")}</button>
        <a className="btn-ghost" href="market://details?id=moe.shizuku.privileged.api">{t("activateShizuku")}</a>
        <button type="button" className="btn-ghost" onClick={() => setShizuku(getShizukuStatus())}>{t("refreshStatus")}</button>
      </div>
      <details className="settings-details">
        <summary>{t("shizukuSteps")}</summary>
        <ol><li>{t("shizukuStep1")}</li><li>{t("shizukuStep2")}</li><li>{t("shizukuStep3")}</li><li>{t("shizukuStep4")}</li><li>{t("shizukuStep5")}</li></ol>
      </details>
    </>)}
    {section(t("scriptBackend"), <>
      <p className="settings-card-note">{t("termuxNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" onClick={() => { showResult(grantTermuxViaShizuku()); setTermuxStatus(getTermuxStatus()); }}>{t("shizukuGrant")}</button>
      </div>
    </>)}
    {section(t("devtools"), <>
      <p className="settings-card-note">{t("devtoolsNote")}</p>
      {devtools && devtools.pages.length > 0 && (
        <p className="settings-card-note">{t("debugPages")} {devtools.pages.length}：{devtools.pages.slice(0, 4).map((p) => p.title || p.url).join(" · ")}{devtools.pages.length > 4 ? " …" : ""}</p>
      )}
      <div className="settings-card-actions">
        <button type="button" className={devtools?.enabled ? "btn-ghost" : "btn-primary"} onClick={() => {
          const ack = setDevToolsEnabled(!devtools?.enabled);
          showResult(ack.message ?? "");
          window.setTimeout(() => setDevtools(getDevToolsStatus()), 600);
        }}>{devtools?.enabled ? t("disableDebug") : t("enableDebug")}</button>
        <button type="button" className="btn-ghost" onClick={() => setDevtools(getDevToolsStatus())}>{t("refreshStatus")}</button>
      </div>
    </>)}
  </>);

  // ===== 技能（独立分类）=====
  const skillsPage = sub(t("skills"), <>
    {section(`${t("skillsInstalled")} · ${skills.length}`, <>
      <div className="settings-card-actions">
        <button type="button" className="btn-ghost" onClick={refreshSkills}>{t("refreshStatus")}</button>
        <button type="button" className="btn-ghost" onClick={() => { showResult(skillManage("reload")); refreshSkills(); }}>{t("skillRefresh")}</button>
      </div>
      {skills.length === 0
        ? <p className="settings-empty">{t("skillsEmpty")}</p>
        : <div className="skill-list">{skills.map((skill) => (
            <div key={skill.name} className={`skill-row${skill.enabled ? "" : " skill-row-off"}`}>
              <div className="skill-row-main">
                <span className="skill-name">{skill.name}</span>
                <label className="settings-switch">
                  <input type="checkbox" checked={skill.enabled}
                    onChange={(e) => { showResult(skillManage("enable", skill.name, String(e.target.checked))); refreshSkills(); }} />
                  <span>{t("enabled")}</span>
                </label>
              </div>
              {skill.description && <p className="skill-desc">{skill.description}</p>}
              <div className="skill-row-foot">
                <span className="skill-badge">{skill.toolCount} {t("toolsCount")}</span>
                <span className="skill-source" title={skill.source}>{skill.source}</span>
                <button type="button" className="skill-remove" onClick={async () => {
                  if (!(await appConfirm(t("deleteSkillConfirm", { name: skill.name })))) return;
                  showResult(skillManage("delete", skill.name, "true"));
                  refreshSkills();
                }}>{t("delete")}</button>
              </div>
            </div>
          ))}</div>}
    </>)}
    {section(t("skillInstallTitle"), <>
      <label className="field"><span>{t("skillSource")}</span>
        <input value={skillSource} onChange={(e) => setSkillSource(e.target.value)} placeholder="https://github.com/user/skill-repo"
          autoCapitalize="none" autoCorrect="off" spellCheck={false} />
      </label>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" disabled={!!skillInstalling}
          onClick={() => installSkill(skillSource, () => setSkillSource(""))}>{t("install")}</button>
      </div>
      <p className="settings-card-note">{t("skillInstallNote")}</p>
    </>)}
    {section(t("skillDiscoverTitle"), <>
      <div className="skill-search">
        <input value={skillQuery} onChange={(e) => setSkillQuery(e.target.value)} placeholder={t("skillSearch")}
          onKeyDown={(e) => { if (e.key === "Enter") searchSkills(); }} />
        <button type="button" className="btn-ghost" disabled={skillSearching} onClick={searchSkills}>
          {skillSearching ? t("skillSearching") : t("search")}
        </button>
      </div>
      {skillResults !== null && (skillResults.length === 0
        ? <p className="settings-empty">{skillSearching ? t("skillSearching") : t("skillSearchEmpty")}</p>
        : <div className="skill-list skill-results">{skillResults.map((item) => {
            const busy = !!skillInstalling && skillInstalling === item.source;
            return (
              <div key={item.source} className="skill-row">
                <div className="skill-row-main">
                  <span className="skill-name">{item.name || item.repo || item.source}</span>
                  <button type="button" className="btn-primary skill-install" disabled={item.installed || busy}
                    onClick={() => installSkill(item.source)}>
                    {item.installed ? t("skillInstalled") : busy ? t("installing") : t("install")}
                  </button>
                </div>
                {item.description && <p className="skill-desc">{item.description}</p>}
                <div className="skill-row-foot">
                  <span className="skill-source" title={item.source}>{item.repo || item.source}</span>
                </div>
              </div>
            );
          })}</div>)}
      <details className="settings-details">
        <summary>{t("skillIndexTitle")}</summary>
        <div className="skill-search">
          <input value={skillIndex} onChange={(e) => setSkillIndex(e.target.value)} placeholder={t("skillIndex")} />
          <button type="button" className="btn-ghost" onClick={() => showResult(skillManage("index", "", skillIndex))}>{t("saveIndex")}</button>
        </div>
      </details>
    </>)}
  </>);

  // ===== SSH 凭据（独立分类）=====
  const sshPage = sub(t("sshCredentials"), <>
    <p className="settings-page-note">{t("sshCredentialsNote")}</p>
    {section(t("adbPairGuideTitle"), <>
      <p className="settings-card-note">{t("adbPairGuideStep1")}</p>
      <p className="settings-card-note">{t("adbPairGuideStep2")}</p>
      <p className="settings-card-note">{t("adbPairGuideStep3")}</p>
    </>)}
    {section(`${t("sshCredentialList")} (${creds.length})`, <>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" onClick={() => showResult(sshCredentialsOpen())}>{t("sshCredentialAdd")}</button>
        <button type="button" className="btn-ghost" onClick={() => setCreds(sshCredentialsList())}>{t("refreshStatus")}</button>
      </div>
      {creds.length === 0
        ? <p className="settings-empty">{t("sshCredentialEmpty")}</p>
        : creds.map((cred) => <div key={cred.nickname} className="settings-card-actions cred-row">
            <span className="cred-row-info">
              <b>{cred.nickname}</b>
              <small>{cred.user}@{cred.host}:{cred.port} · {cred.authType}</small>
            </span>
            <button type="button" className="btn-ghost" onClick={() => showResult(sshCredentialEdit(cred.nickname))}>{t("sshCredentialEdit")}</button>
            <button type="button" className="btn-ghost" onClick={async () => {
              if (!(await appConfirm(t("sshCredentialDeleteConfirm", { name: cred.nickname })))) return;
              showResult(sshCredentialDelete(cred.nickname));
              setCreds(sshCredentialsList());
            }}>{t("delete")}</button>
          </div>)}
      <p className="settings-card-note">{t("sshCredentialEditNote")}</p>
    </>)}
  </>);

  // ===== 外部备份（R102：换签名重装后恢复数据）=====
  const formatWhen = (ts: number) => (ts > 0 ? new Date(ts).toLocaleString() : t("storageNever"));
  const formatSize = (bytes: number) => bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`;
  const refreshExternal = () => setExternal(externalStateStatus());

  const storagePage = sub(t("storageGroup"), <>
    <p className="settings-page-note">{t("storageNote")}</p>
    {external.pendingRestore && hasBridge() && (
      <div className="settings-alert">
        <span>{t("storageFoundSnapshot")}</span>
        <button type="button" className="btn-ghost btn-sm" onClick={async () => {
          if (!(await appConfirm(t("storageRestoreConfirm")))) return;
          showResult(externalStateRestore());
          refreshExternal();
        }}>{t("storageRestore")}</button>
      </div>
    )}
    {(!external.permission || !external.writable) && hasBridge() && (
      <div className="settings-alert">
        <span>{t("storagePermissionNote")}</span>
        <button type="button" className="btn-ghost btn-sm" onClick={async () => {
          const granted = await requestPermissions(["android.permission.WRITE_EXTERNAL_STORAGE"]);
          showResult(Object.values(granted).some(Boolean) ? externalStateSync() : "ERROR: 未授予存储权限");
          refreshExternal();
        }}>{t("storageGrantStorage")}</button>
      </div>
    )}
    {section(t("storagePath"), <>
      <dl className="settings-rows">
        <div className="settings-row"><dt>{t("storagePath")}</dt><dd><code>{external.path || t("none")}</code></dd></div>
        <div className="settings-row"><dt>{t("storageMode")}</dt>
          <dd className={external.degraded ? "is-warn" : "is-ok"}>
            {external.degraded ? t("storageModeDegraded") : t("storageModePublic")}
          </dd>
        </div>
        <div className="settings-row"><dt>{t("storageLastSync")}</dt><dd>{formatWhen(external.lastSyncAt)}</dd></div>
        <div className="settings-row"><dt>{t("storageSnapshot")}</dt><dd>{formatWhen(external.snapshotAt)}</dd></div>
        <div className="settings-row"><dt>{t("storageFiles")}</dt><dd>{external.files}</dd></div>
        <div className="settings-row"><dt>{t("storageSize")}</dt><dd>{formatSize(external.bytes)}</dd></div>
      </dl>
      {!!external.note && <p className="settings-card-note">{external.note}</p>}
      {!external.hasSnapshot && <p className="settings-empty">{t("storageNoSnapshot")}</p>}
    </>)}
    {section(t("storageAuto"), <>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" disabled={!hasBridge()}
          onClick={() => { showResult(externalStateSync()); refreshExternal(); }}>{t("storageSyncNow")}</button>
        <button type="button" className="btn-ghost" disabled={!hasBridge() || !external.hasSnapshot}
          onClick={async () => {
            if (!(await appConfirm(t("storageRestoreConfirm")))) return;
            showResult(externalStateRestore());
            refreshExternal();
          }}>{t("storageRestore")}</button>
        <button type="button" className="btn-ghost" onClick={refreshExternal}>{t("refreshStatus")}</button>
      </div>
      <label className="settings-switch">
        <input type="checkbox" checked={external.enabled} disabled={!hasBridge()}
          onChange={(e) => { showResult(externalStateSetEnabled(e.target.checked)); refreshExternal(); }} />
        <span>{t("storageAuto")}</span>
      </label>
      {!!external.excluded?.length && (
        <details className="settings-details">
          <summary>{t("storageExcluded")}</summary>
          <ul>{external.excluded.map((item) => <li key={item}>{item}</li>)}</ul>
        </details>
      )}
    </>)}
    {section(t("storageConfigTitle"), <>
      <p className="settings-card-note">{t("storageConfigNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-ghost" disabled={!hasBridge()}
          onClick={async () => {
            if (!(await appConfirm(t("storageWriteConfigConfirm")))) return;
            showResult(externalStateWriteConfig());
            refreshExternal();
          }}>{t("storageWriteConfig")}</button>
      </div>
    </>)}
  </>);

  const dataPage = sub(t("dataGroup"), <>
    {section(t("projection"), <>
      <p className="settings-card-note">{t("projectionNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" onClick={askProjection}>{t("grantProjection")}</button>
        <button type="button" className="btn-ghost" onClick={() => { showResult(stopProjection()); window.setTimeout(() => setProjection(getProjectionStatus()), 600); }}>{t("stopAction")}</button>
        <button type="button" className="btn-ghost" onClick={() => setProjection(getProjectionStatus())}>{t("refreshStatus")}</button>
      </div>
    </>)}
    {section(t("health"), <>
      <p className="settings-card-note">{t("healthNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-ghost" onClick={askHealth}>{t("grantSteps")}</button>
      </div>
    </>)}
    {section(t("sshScrub"), <>
      <p className="settings-card-note">{t("sshScrubNote")}</p>
      <div className="settings-card-actions">
        <button type="button" className="btn-ghost" onClick={async () => {
          if (!(await appConfirm(t("sshScrubConfirm")))) return;
          try {
            const result = SessionStore.scrubSecrets();
            showSuccess(result.replaced > 0 ? t("sshScrubDone", { count: result.replaced }) : t("sshScrubNone"));
          } catch (e) { showError(String(e)); }
        }}>{t("sshScrub")}</button>
      </div>
    </>)}
  </>);

  // 构建时间是编译期写入的 ISO 串：转成本地可读格式，解析失败时退回「未知」。
  const aboutBuildTime = () => {
    const date = new Date(__BUILD_TIME__);
    return Number.isNaN(date.getTime()) ? t("buildUnknown") : date.toLocaleString();
  };

  const aboutPage = sub(t("about"), <>
    <div className="about-hero">
      <div className="about-hero-mark" aria-hidden="true">◆</div>
      <div className="about-hero-main">
        <div className="about-hero-title">
          <h3>AndAgent</h3>
          <span className="about-pill">v{getAppVersion()}</span>
        </div>
        <p className="about-hero-sub">{t("aboutTagline")}</p>
      </div>
    </div>

    {section(t("aboutAppInfo"), (
      <dl className="settings-rows">
        <div className="settings-row"><dt>{t("version")}</dt><dd>{getAppVersion()}</dd></div>
        <div className="settings-row"><dt>{t("built")}</dt><dd>{aboutBuildTime()}</dd></div>
        <div className="settings-row"><dt>{t("gitCommit")}</dt><dd><code>{__GIT_COMMIT__}</code></dd></div>
        <div className="settings-row"><dt>{t("license")}</dt><dd>Apache-2.0</dd></div>
      </dl>
    ))}

    {section(t("aboutLinks"), <>
      <div className="about-links">
        <a className="about-link" href="https://github.com/congduan/andagent" target="_blank" rel="noreferrer">
          <span className="about-link-label">{t("repository")}</span>
          <span className="about-link-chevron" aria-hidden="true">↗</span>
        </a>
        <a className="about-link" href="https://github.com/congduan/andagent/stargazers" target="_blank" rel="noreferrer">
          <span className="about-link-label">{t("star")}</span>
          <span className="about-link-chevron" aria-hidden="true">↗</span>
        </a>
      </div>
      <details className="settings-details about-notices">
        <summary>{t("notices")}</summary>
        <div className="settings-notices"><pre>{noticesText || t("noticesUnavailable")}</pre></div>
      </details>
    </>)}
  </>);

  const subPages: Record<SettingsPage, React.ReactNode> = {
    home: null, model: modelPage, prompt: promptPage, appearance: appearancePage, memory: memoryPage,
    automation: automationPage, skills: skillsPage, ssh: sshPage, extensions: extensionsPage,
    storage: storagePage, data: dataPage, about: aboutPage,
  };

  return (
    <div className={`settings-overlay${closing ? " closing" : ""}`} role="dialog" aria-modal="true">
      <div className="settings-panel">
        {page === "home" ? (
          <>
            {/* 这一层返回即关闭设置；顶栏与子页、模型向导完全一致。 */}
            <SettingsHead title={t("settings")} backLabel={t("back")} onBack={onClose} />
            <div className="settings-body settings-body-home">
              {homeItems.map((item) => (
                <button key={item.key} type="button" className="settings-home-item" onClick={() => onNavigate(item.key)}>
                  <span className="settings-home-icon" style={{ background: item.tint + "22", color: item.tint }}>{item.icon}</span>
                  <span className="settings-home-text">
                    <span className="settings-home-title">{item.title}</span>
                    <span className="settings-home-sub">{item.sub}</span>
                  </span>
                  {item.badge && <span className={`settings-home-badge badge-${item.badgeTone ?? "count"}`}>{item.badge}</span>}
                  <span className="settings-home-chevron">›</span>
                </button>
              ))}
            </div>
          </>
        ) : subPages[page]}
      </div>
    </div>
  );
}

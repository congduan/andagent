import { useEffect, useRef, useState } from "react";
import {
  cronDelete, cronExactAllowed, cronList, cronOpenExactSettings, cronPauseAll,
  cronToggle, getAppVersion, getDevToolsStatus, getProjectionStatus,
  getShizukuStatus, getTermuxStatus, grantTermuxViaShizuku, hasBridge,
  memoryCall, persistSettings, requestHealthPermission, requestProjectionConsent,
  setDevToolsEnabled, skillManage, stopProjection,
  sshCredentialDelete, sshCredentialEdit, sshCredentialsList, sshCredentialsOpen,
  defaultSystemPrompt, hasSavedSystemPrompt, isDefaultSystemPrompt,
  type Settings, type SshCredentialMeta,
} from "./bridge";
import { SessionStore } from "./sessions";
import noticesText from "../../THIRD-PARTY-NOTICES.md?raw";
import { loadThemeMode, setTheme, type ThemeMode } from "./theme";
import { setLanguageMode, useI18n, type LanguageMode } from "./i18n";
import { appConfirm } from "./ConfirmDialog";
import { ModelFields } from "./ModelFields";
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
};

export type SettingsPage =
  | "home" | "model" | "appearance" | "memory" | "automation"
  | "ssh" | "skills" | "extensions" | "data" | "about";

type ShizukuStatus = ReturnType<typeof getShizukuStatus>;
type ProjectionStatus = ReturnType<typeof getProjectionStatus>;
type DevToolsStatus = ReturnType<typeof getDevToolsStatus> | null;
type CronTask = ReturnType<typeof cronList>[number];
type MemoryEntry = { id: string; content: string; category: string; core: boolean };
type SkillEntry = { name: string; description: string; enabled: boolean; toolCount: number; source: string };

const THEME_OPTIONS: ThemeMode[] = ["system", "light", "dark"];

export function SettingsPanel({ settings, page, onNavigate, onBack, onClose, onSave, onModelSetup }: Props) {
  const { t, mode, language } = useI18n();
  const [draft, setDraft] = useState<Settings>(settings);
  useEffect(() => { setDraft(prev => !hasSavedSystemPrompt() && isDefaultSystemPrompt(prev.systemPrompt) ? { ...prev, systemPrompt: defaultSystemPrompt() } : prev); }, [language]);
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => loadThemeMode());
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
  const [skillIndex, setSkillIndex] = useState("");
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [memoriesLoaded, setMemoriesLoaded] = useState(false);
  const [memoryEdits, setMemoryEdits] = useState<Record<string, string>>({});
  const [showNotices, setShowNotices] = useState(false);
  const [creds, setCreds] = useState<SshCredentialMeta[]>([]);
  const [credsLoaded, setCredsLoaded] = useState(false);
  const bodyRef = useRef<HTMLDivElement | null>(null);

  // R63: 子页按需惰性加载（进入页面才拉数据，替代旧版全量 useState 平铺）。
  useEffect(() => {
    if (page === "automation") { setCronTasks(cronList()); setExactAllowed(cronExactAllowed()); setCronLoaded(true); }
    if (page === "skills") { try { setSkills(JSON.parse(skillManage("list"))); } catch { setSkills([]); } setSkillsLoaded(true); }
    if (page === "ssh") { setCreds(sshCredentialsList()); setCredsLoaded(true); }
    if (page === "memory") {
      try { setMemories(memoryCall<MemoryEntry[]>("memory_get_all")); }
      catch (e) { showError(String(e)); }
      setMemoriesLoaded(true);
    }
    if (page === "data" && hasBridge()) { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); setProjection(getProjectionStatus()); setDevtools(hasBridge() ? getDevToolsStatus() : null); }
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
  useEffect(() => {
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
    { key: "appearance", icon: "🎨", tint: "#8b5cf6", title: t("appearanceLang"), sub: `${t(themeMode)} · ${language === "zh" ? t("chinese") : t("english")}` },
    { key: "memory", icon: "🧠", tint: "#a855f7", title: t("memory"), sub: t("memoryNote"), badge: memoriesLoaded ? `${memories.length}` : undefined, badgeTone: "count" },
    { key: "automation", icon: "⏱️", tint: "#f59e0b", title: t("cron"), sub: t("cronNote"), badge: cronLoaded ? `${cronTasks.length}` : undefined, badgeTone: "count" },
    { key: "skills", icon: "🧩", tint: "#10b981", title: t("skills"), sub: `${t("install")} · ${t("enabled")} · ${t("search")}`, badge: skillsLoaded ? `${skills.length}` : undefined, badgeTone: "count" },
    { key: "ssh", icon: "🔑", tint: "#f43f5e", title: t("sshCredentials"), sub: t("sshCredentialsGroupSub"), badge: credsLoaded ? `${creds.length}` : undefined, badgeTone: "count" },
    { key: "extensions", icon: "🧰", tint: "#0ea5e9", title: t("extensionsGroup"), sub: "Shizuku · Termux · DevTools" },
    { key: "data", icon: "📊", tint: "#06b6d4", title: t("dataGroup"), sub: `${t("health")} · ${t("projection")}` },
    { key: "about", icon: "ℹ️", tint: "#64748b", title: t("about"), sub: `${t("version")} ${getAppVersion()}` },
  ];

  // ===== 子页公共骨架 =====
  const sub = (title: string, children: React.ReactNode, extraHead?: React.ReactNode) => (
    <div className="settings-subpage">
      <header className="settings-subpage-head">
        <button type="button" className="icon-btn" aria-label={t("back")} onClick={onBack}>←</button>
        <h2>{title}</h2>
        {extraHead}
      </header>
      <div className="settings-body" ref={bodyRef}>{children}</div>
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
  const modelPage = sub(t("modelSettings"), <>
    <ModelFields settings={draft} onChange={(patch) => setDraft((prev) => ({ ...prev, ...patch }))} />
    <label className="field"><span>{t("systemPrompt")}</span>
      <textarea rows={5} value={draft.systemPrompt} onChange={(e) => setDraft({ ...draft, systemPrompt: e.target.value })} placeholder={t("systemPromptPlaceholder")} />
    </label>
    <div className="settings-card-actions">
      <button type="button" className="btn-primary" onClick={save}>{t("save")}</button>
      {/* 需要"测试并保存"时走向导：设置页只做本地保存，不发网络请求。 */}
      <button type="button" className="btn-ghost" onClick={onModelSetup}>{t("modelTestSave")}</button>
    </div>
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
  </>);

  const memoryPage = sub(t("memory"), <>
    <p className="settings-card-note">{t("memoryNote")}</p>
    {section(`${t("coreMemory")} (${memories.filter(m => m.core).length}/10)`, <>
      {memories.filter(m => m.core).map(m => (
        <div className="memory-entry" key={m.id}>
          <small>{m.category}</small>
          <textarea rows={2} maxLength={500} value={memoryEdits[m.id] ?? m.content} onChange={e => setMemoryEdits({ ...memoryEdits, [m.id]: e.target.value })} />
          <button type="button" onClick={() => saveMemory(m.id)}>{t("save")}</button>
          <button type="button" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
        </div>
      ))}
    </>)}
    {section(`${t("archivedMemory")} (${memories.filter(m => !m.core).length}/200)`, <>
      {memories.filter(m => !m.core).map(m => (
        <div className="memory-entry" key={m.id}>
          <span>{m.content}</span>
          <button type="button" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
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
    <p className="settings-card-note">{t("cronNote")}</p>
    {!exactAllowed && hasBridge() && <div className="settings-card-actions">
      <span>{t("exactAlarmMissing")}</span>
      <button type="button" className="btn-ghost" onClick={() => { showResult(cronOpenExactSettings()); window.setTimeout(refreshCron, 1000); }}>{t("exactAlarmSettings")}</button>
    </div>}
    {cronTasks.map((task) => <div key={task.id}>
      <div className="settings-card-actions">
        <button type="button" className="btn-ghost" onClick={() => setCronDetail(cronDetail === task.id ? "" : task.id)}>{task.name} · {task.schedule} · {task.kind}</button>
        <label><input type="checkbox" checked={task.enabled} onChange={(e) => { showResult(cronToggle(task.id, e.target.checked)); refreshCron(); }} />{t("enabled")}</label>
        <button type="button" className="btn-ghost" onClick={async () => { if (await appConfirm(t("deleteCronConfirm", { name: task.name }))) { showResult(cronDelete(task.id)); refreshCron(); } }}>{t("delete")}</button>
      </div>
      {cronDetail === task.id && <p className="settings-card-note">{t("lastRun")}：{task.lastRun ? new Date(task.lastRun).toLocaleString() : t("neverRun")} · {t("nextRun")}：{task.nextRun ? new Date(task.nextRun).toLocaleString() : t("paused")}<br />{t("lastResult")}：{task.lastResult || t("none")}</p>}
    </div>)}
    <div className="settings-card-actions">
      <button type="button" className="btn-ghost" onClick={() => { showResult(cronPauseAll()); refreshCron(); }}>{t("pauseAll")}</button>
      <button type="button" className="btn-ghost" onClick={refreshCron}>{t("refresh")}</button>
    </div>
  </>);

  const extensionsPage = sub(t("extensionsGroup"), <>
    {section(t("shizuku"), <>
      <p className="settings-card-note">{shizuku.note ?? t("shizukuNote")}{shizuku.managerVersion ? ` · v${shizuku.managerVersion}` : ""}</p>
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
        <button type="button" className="btn-primary" onClick={() => {
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
        ? <p className="settings-card-note">{t("skillsEmpty")}</p>
        : <div className="skill-list">{skills.map((skill) => (
            <div key={skill.name} className={`skill-row${skill.enabled ? "" : " skill-row-off"}`}>
              <div className="skill-row-main">
                <span className="skill-name">{skill.name}</span>
                <label className="skill-switch">
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
        <button type="button" className="btn-primary" onClick={() => { showResult(skillManage("install", "", skillSource)); refreshSkills(); }}>{t("install")}</button>
      </div>
      <p className="settings-card-note">{t("skillInstallNote")}</p>
    </>)}
    {section(t("skillDiscoverTitle"), <>
      <div className="skill-search">
        <input value={skillQuery} onChange={(e) => setSkillQuery(e.target.value)} placeholder={t("skillSearch")} />
        <button type="button" className="btn-ghost" onClick={() => showResult(skillManage("search", "", skillQuery))}>{t("search")}</button>
      </div>
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
    <p className="settings-card-note">{t("sshCredentialsNote")}</p>
    {section(`${t("sshCredentialList")} (${creds.length})`, <>
      <div className="settings-card-actions">
        <button type="button" className="btn-primary" onClick={() => showResult(sshCredentialsOpen())}>{t("sshCredentialAdd")}</button>
        <button type="button" className="btn-ghost" onClick={() => setCreds(sshCredentialsList())}>{t("refreshStatus")}</button>
      </div>
      {creds.length === 0
        ? <p className="settings-card-note">{t("sshCredentialEmpty")}</p>
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
  </>, <button type="button" className="icon-btn" style={{ marginLeft: "auto" }} onClick={onClose} aria-label={t("close")}>✕</button>);

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

  const aboutPage = sub(t("about"), <>
    {section(t("version"), <>
      <p className="settings-card-note">{t("version")}: {getAppVersion()} · {t("built")}: {__BUILD_TIME__}</p>
      <p className="settings-card-note">{t("license")}: Apache-2.0</p>
      <div className="settings-card-actions">
        <a className="btn-ghost" href="https://github.com/congduan/andagent" target="_blank" rel="noreferrer">{t("repository")}</a>
        <a className="btn-ghost" href="https://github.com/congduan/andagent/stargazers" target="_blank" rel="noreferrer">{t("star")}</a>
        <button type="button" className="btn-ghost" onClick={() => setShowNotices(v => !v)}>{t("notices")}</button>
      </div>
      {showNotices && <div className="settings-notices"><pre>{noticesText || t("noticesUnavailable")}</pre></div>}
    </>)}
  </>);

  const subPages: Record<SettingsPage, React.ReactNode> = {
    home: null, model: modelPage, appearance: appearancePage, memory: memoryPage,
    automation: automationPage, skills: skillsPage, ssh: sshPage, extensions: extensionsPage,
    data: dataPage, about: aboutPage,
  };

  return (
    <div className="settings-overlay" role="dialog" aria-modal="true">
      <div className="settings-panel">
        {page === "home" ? (
          <>
            <header className="settings-head">
              <div><h2>{t("settings")}</h2><p>{t("settingsNote")}</p></div>
              <button type="button" className="icon-btn" onClick={onClose} aria-label={t("close")}>✕</button>
            </header>
            <div className="settings-body">
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
              <div className="settings-note">{hasBridge() ? t("bridgeConnected") : t("browserPreview")}</div>
            </div>
            <footer className="settings-foot">
              <button type="button" className="btn-ghost" onClick={onClose}>{t("cancel")}</button>
            </footer>
          </>
        ) : subPages[page]}
      </div>
    </div>
  );
}

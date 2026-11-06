import { useEffect, useState } from "react";
import type { Settings } from "./bridge";
import {
  defaultSystemPrompt,
  isDefaultSystemPrompt,
  hasSavedSystemPrompt,
  getDevToolsStatus,
  getProjectionStatus,
  getShizukuStatus,
  getTermuxStatus,
  grantTermuxViaShizuku,
  hasBridge,
  persistSettings,
  requestHealthPermission,
  requestProjectionConsent,
  requestShizukuPermission,
  setDevToolsEnabled,
  stopProjection,
  type DevToolsStatus,
  type ProjectionStatus,
  type ShizukuStatus,
  cronList, cronToggle, cronDelete, cronPauseAll, cronExactAllowed, cronOpenExactSettings,
  type CronTask, skillManage, memoryCall, type MemoryEntry,
  getAppVersion,
} from "./bridge";
import noticesText from "../../THIRD-PARTY-NOTICES.md?raw";
import { setLanguageMode, useI18n, type LanguageMode } from "./i18n";
import { loadThemeMode, setTheme, type ThemeMode } from "./theme";

const THEME_OPTIONS: ThemeMode[] = ["system", "light", "dark"];

interface Props {
  settings: Settings;
  onClose: () => void;
  onSave: (settings: Settings) => void;
  onModelSetup: () => void;
}

export function SettingsPanel({ settings, onClose, onSave, onModelSetup }: Props) {
  const { t, mode, language } = useI18n();
  const [showNotices, setShowNotices] = useState(false);
  const [draft, setDraft] = useState<Settings>(settings);
  useEffect(() => { setDraft(prev => ({ ...prev, baseUrl: settings.baseUrl, apiKey: settings.apiKey, model: settings.model })); }, [settings.baseUrl, settings.apiKey, settings.model]);
  useEffect(() => { setDraft(prev => !hasSavedSystemPrompt() && isDefaultSystemPrompt(prev.systemPrompt) ? { ...prev, systemPrompt: defaultSystemPrompt() } : prev); }, [language]);
  const [error, setError] = useState<string | null>(null);
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => loadThemeMode());
  const [shizuku, setShizuku] = useState<ShizukuStatus>(() => getShizukuStatus());
  const [shizukuMsg, setShizukuMsg] = useState<string | null>(null);
  const [termuxStatus, setTermuxStatus] = useState(() => getTermuxStatus());
  const [termuxMsg, setTermuxMsg] = useState<string | null>(null);
  const [cronTasks, setCronTasks] = useState<CronTask[]>(() => cronList());
  const [cronMsg, setCronMsg] = useState("");
  const [cronDetail, setCronDetail] = useState("");
  const [exactAllowed, setExactAllowed] = useState(() => cronExactAllowed());
  const refreshCron = () => { setCronTasks(cronList()); setExactAllowed(cronExactAllowed()); };
  const [healthMsg, setHealthMsg] = useState<string | null>(null);
  const [projection, setProjection] = useState<ProjectionStatus>(() => getProjectionStatus());
  const [projectionMsg, setProjectionMsg] = useState<string | null>(null);
  const [devtools, setDevtools] = useState<DevToolsStatus | null>(() => (hasBridge() ? getDevToolsStatus() : null));
  const [devtoolsMsg, setDevtoolsMsg] = useState<string | null>(null);
  const [skills, setSkills] = useState<Array<{name:string; description:string; enabled:boolean; toolCount:number; source:string}>>([]);
  const [skillSource, setSkillSource] = useState("");
  const [skillQuery, setSkillQuery] = useState("");
  const [skillIndex, setSkillIndex] = useState("");
  const [skillMsg, setSkillMsg] = useState("");
  const refreshSkills = () => { try { setSkills(JSON.parse(skillManage("list"))); } catch { setSkills([]); } };
  useEffect(refreshSkills, []);
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [memoryEdits, setMemoryEdits] = useState<Record<string, string>>({});
  const [memoryMsg, setMemoryMsg] = useState("");
  const refreshMemories = () => {
    try { setMemories(memoryCall<MemoryEntry[]>("memory_get_all")); }
    catch (e) { setMemoryMsg(String(e)); }
  };
  useEffect(refreshMemories, []);
  const saveMemory = (id: string) => {
    try { memoryCall("memory_update", { id, content: memoryEdits[id] ?? memories.find(m => m.id === id)?.content ?? "" }); setMemoryMsg(t("saved")); refreshMemories(); }
    catch (e) { setMemoryMsg(String(e)); }
  };
  const deleteMemory = (id: string) => {
    try { memoryCall("memory_delete", { id }); refreshMemories(); }
    catch (e) { setMemoryMsg(String(e)); }
  };


  useEffect(() => {
    if (!hasBridge()) return;
    const refresh = () => { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); setProjection(getProjectionStatus()); };
    refresh();
    const timer = window.setInterval(refresh, 4000);
    return () => window.clearInterval(timer);
  }, []);

  const askShizuku = () => {
    const ack = requestShizukuPermission();
    setShizukuMsg(ack.message ?? null);
    window.setTimeout(() => setShizuku(getShizukuStatus()), 800);
  };

  const askHealth = () => {
    const ack = requestHealthPermission();
    setHealthMsg(ack.message ?? null);
  };

  const askProjection = () => {
    const ack = requestProjectionConsent();
    setProjectionMsg(ack.message ?? null);
    window.setTimeout(() => setProjection(getProjectionStatus()), 1500);
  };

  const shizukuLabel =
    shizuku.status === "granted"
      ? t("granted")
      : shizuku.status === "not_granted"
        ? t("notGranted")
        : t("unavailable");

  const projectionLabel =
    projection.channel === "projection"
      ? projection.recording ? t("recording") : t("projectionGranted")
      : projection.channel === "shizuku"
        ? t("viaShizuku")
        : t("notConfigured");

  const save = () => {
    const next: Settings = {
      ...draft,
      baseUrl: draft.baseUrl.trim().replace(/\/+$/, ""),
      model: draft.model.trim(),
      systemPrompt: draft.systemPrompt.trim(),
    };
    try {
      persistSettings(next);
      onSave(next);
      onClose();
    } catch { setError(t("modelSaveFailed")); }
  };

  return (
    <div className="settings-overlay" role="dialog" aria-modal="true">
      <div className="settings-panel">
        <header className="settings-head">
          <div>
            <h2>{t("settings")}</h2>
            <p>{t("settingsNote")}</p>
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t("close")}>
            ✕
          </button>
        </header>

        <div className="settings-body">
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("modelSettings")}</span></div>
            <p className="settings-card-note">{draft.model || t("modelNotConfigured")}</p>
            <p className="settings-card-note model-endpoint">{draft.baseUrl}</p>
            <button type="button" className="btn-primary" onClick={onModelSetup}>{t("modelConfigure")}</button>
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("about")}</span></div>
            <p className="settings-card-note">{t("version")}: {getAppVersion()} · {t("built")}: {__BUILD_TIME__}</p>
            <p className="settings-card-note">{t("license")}: Apache-2.0</p>
            <div className="settings-card-actions">
              <a className="btn-ghost" href="https://github.com/congduan/andagent" target="_blank" rel="noreferrer">{t("repository")}</a>
              <a className="btn-ghost" href="https://github.com/congduan/andagent/stargazers" target="_blank" rel="noreferrer">{t("star")}</a>
              <button type="button" className="btn-ghost" onClick={() => setShowNotices(v => !v)}>{t("notices")}</button>
            </div>
            {showNotices && <div className="settings-notices"><pre>{noticesText || t("noticesUnavailable")}</pre></div>}
          </div>
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("language")}</span></div>
            <div className="theme-segmented" role="radiogroup" aria-label={t("language")}>
              {(["system", "zh", "en"] as LanguageMode[]).map(value => <button key={value} type="button" role="radio" aria-checked={mode === value} className={mode === value ? "active" : ""} onClick={() => setLanguageMode(value)}>{value === "system" ? t("system") : value === "zh" ? t("chinese") : t("english")}</button>)}
            </div>
          </div>
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("appearance")}</span></div>
            <div className="theme-segmented" role="radiogroup" aria-label={t("appearance")}>
              {THEME_OPTIONS.map((option) => (
                <button
                  key={option}
                  type="button"
                  role="radio"
                  aria-checked={themeMode === option}
                  className={themeMode === option ? "active" : ""}
                  onClick={() => { setTheme(option); setThemeMode(option); }}
                >
                  {t(option)}
                </button>
              ))}
            </div>
            <p className="settings-card-note">{t("themeNote")}</p>
          </div>


          <label className="field">
            <span>{t("systemPrompt")}</span>
            <textarea
              rows={5}
              value={draft.systemPrompt}
              onChange={(e) => setDraft({ ...draft, systemPrompt: e.target.value })}
              placeholder={t("systemPromptPlaceholder")}
            />
          </label>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("memory")}</span></div>
            <p className="settings-card-note">{t("memoryNote")}</p>
            <strong>{t("coreMemory")} ({memories.filter(m => m.core).length}/10)</strong>
            {memories.filter(m => m.core).map(m => (
              <div className="memory-entry" key={m.id}>
                <small>{m.category}</small>
                <textarea rows={2} maxLength={500} value={memoryEdits[m.id] ?? m.content}
                  onChange={e => setMemoryEdits({ ...memoryEdits, [m.id]: e.target.value })} />
                <button type="button" onClick={() => saveMemory(m.id)}>{t("save")}</button>
                <button type="button" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
              </div>
            ))}
            <strong>{t("archivedMemory")} ({memories.filter(m => !m.core).length}/200)</strong>
            {memories.filter(m => !m.core).map(m => (
              <div className="memory-entry" key={m.id}>
                <span>{m.content}</span>
                <button type="button" onClick={() => deleteMemory(m.id)}>{t("delete")}</button>
              </div>
            ))}
            <button type="button" onClick={() => {
              if (!window.confirm(t("clearConfirm"))) return;
              try { memoryCall("memory_delete_all", { confirm: true }); refreshMemories(); setMemoryMsg(t("cleared")); }
              catch (e) { setMemoryMsg(String(e)); }
            }}>{t("clearMemory")}</button>
            {memoryMsg && <p role="status">{memoryMsg}</p>}
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("voiceSettings")}</span></div>
            <label className="field">
              <span>{t("transcribeModel")}</span>
              <input
                value={draft.transcribeModel ?? ""}
                onChange={(e) => setDraft({ ...draft, transcribeModel: e.target.value })}
                placeholder="whisper-1 / glm-asr / fun-asr"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
              <em>{t("transcribeModelHint")}</em>
            </label>
            <label className="field">
              <span>{t("transcribeEndpoint")}</span>
              <input
                value={draft.transcribeBaseUrl ?? ""}
                onChange={(e) => setDraft({ ...draft, transcribeBaseUrl: e.target.value })}
                placeholder="https://other-stt.example.com/v1"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
            </label>
            <label className="field">
              <span>{t("ttsEndpoint")}</span>
              <input
                value={draft.ttsBaseUrl ?? ""}
                onChange={(e) => setDraft({ ...draft, ttsBaseUrl: e.target.value })}
                placeholder="https://tts.example.com/v1"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
            </label>
            <label className="field">
              <span>{t("ttsVoice")}</span>
              <div style={{ display: "flex", gap: 8 }}>
                <input
                  value={draft.ttsModel ?? ""}
                  onChange={(e) => setDraft({ ...draft, ttsModel: e.target.value })}
                  placeholder="tts-1"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                />
                <input
                  value={draft.ttsVoice ?? ""}
                  onChange={(e) => setDraft({ ...draft, ttsVoice: e.target.value })}
                  placeholder="alloy"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                />
              </div>
            </label>
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("skills")}</span><span>{skills.length} {t("skillsCount")}</span></div>
            {skills.map((skill) => <div key={skill.name} className="settings-card-actions">
              <span title={skill.source}>{skill.name} · {skill.description} · {skill.toolCount} {t("toolsCount")}</span>
              <label><input type="checkbox" checked={skill.enabled} onChange={(e) => { setSkillMsg(skillManage("enable", skill.name, String(e.target.checked))); refreshSkills(); }} />{t("enabled")}</label>
              <button type="button" className="btn-ghost" onClick={() => { if (window.confirm(t("deleteSkillConfirm", { name: skill.name }))) { setSkillMsg(skillManage("delete", skill.name, "true")); refreshSkills(); } }}>{t("delete")}</button>
            </div>)}
            <div className="settings-card-actions"><input value={skillSource} onChange={(e) => setSkillSource(e.target.value)} placeholder={t("skillSource")} /><button type="button" className="btn-ghost" onClick={() => { setSkillMsg(skillManage("install", "", skillSource)); refreshSkills(); }}>{t("install")}</button></div>
            <div className="settings-card-actions"><input value={skillQuery} onChange={(e) => setSkillQuery(e.target.value)} placeholder={t("skillSearch")} /><button type="button" className="btn-ghost" onClick={() => setSkillMsg(skillManage("search", "", skillQuery))}>{t("search")}</button><button type="button" className="btn-ghost" onClick={() => { setSkillMsg(skillManage("reload")); refreshSkills(); }}>{t("skillRefresh")}</button></div>
            <div className="settings-card-actions"><input value={skillIndex} onChange={(e) => setSkillIndex(e.target.value)} placeholder={t("skillIndex")} /><button type="button" className="btn-ghost" onClick={() => setSkillMsg(skillManage("index", "", skillIndex))}>{t("saveIndex")}</button></div>
            {skillMsg && <p className="settings-card-note">{skillMsg.slice(0, 1200)}</p>}
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("cron")}</span><span>{cronTasks.length} {t("taskCount")}</span></div>
            <p className="settings-card-note">{t("cronNote")}</p>
            {!exactAllowed && hasBridge() && <div className="settings-card-actions">
              <span>{t("exactAlarmMissing")}</span>
              <button type="button" className="btn-ghost" onClick={() => { setCronMsg(cronOpenExactSettings()); window.setTimeout(refreshCron, 1000); }}>{t("exactAlarmSettings")}</button>
            </div>}
            {cronTasks.map((task) => <div key={task.id}>
              <div className="settings-card-actions">
                <button type="button" className="btn-ghost" onClick={() => setCronDetail(cronDetail === task.id ? "" : task.id)}>{task.name} · {task.schedule} · {task.kind}</button>
                <label><input type="checkbox" checked={task.enabled} onChange={(e) => { setCronMsg(cronToggle(task.id, e.target.checked)); refreshCron(); }} />{t("enabled")}</label>
                <button type="button" className="btn-ghost" onClick={() => { if (window.confirm(t("deleteCronConfirm", { name: task.name }))) { setCronMsg(cronDelete(task.id)); refreshCron(); } }}>{t("delete")}</button>
              </div>
              {cronDetail === task.id && <p className="settings-card-note">{t("lastRun")}：{task.lastRun ? new Date(task.lastRun).toLocaleString() : t("neverRun")} · {t("nextRun")}：{task.nextRun ? new Date(task.nextRun).toLocaleString() : t("paused")}<br />{t("lastResult")}：{task.lastResult || t("none")}</p>}
            </div>)}
            <div className="settings-card-actions">
              <button type="button" className="btn-ghost" onClick={() => { setCronMsg(cronPauseAll()); refreshCron(); }}>{t("pauseAll")}</button>
              <button type="button" className="btn-ghost" onClick={refreshCron}>{t("refresh")}</button>
            </div>
            {cronMsg && <p className={cronMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{cronMsg}</p>}
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("shizuku")}</span>
              <span className={`status-pill status-${shizuku.status}`}>{shizukuLabel}</span>
            </div>
            <p className="settings-card-note">
              {shizuku.note ?? t("shizukuNote")}
              {shizuku.managerVersion ? ` · v${shizuku.managerVersion}` : ""}
            </p>
            {shizukuMsg && <p className="settings-card-note">{shizukuMsg}</p>}
            {shizuku.lastRequestError && (
              <p className="settings-error">{t("authorizationFailed")}：{shizuku.lastRequestError}</p>
            )}
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={askShizuku}>{t("requestAccess")}</button>
              <a
                className="btn-ghost"
                href="market://details?id=moe.shizuku.privileged.api"
              >{t("activateShizuku")}</a>
              <button type="button" className="btn-ghost" onClick={() => setShizuku(getShizukuStatus())}>{t("refreshStatus")}</button>
            </div>
            <details className="settings-details">
              <summary>{t("shizukuSteps")}</summary>
              <ol>
                <li>{t("shizukuStep1")}</li>
                <li>{t("shizukuStep2")}</li>
                <li>{t("shizukuStep3")}</li>
                <li>{t("shizukuStep4")}</li>
                <li>{t("shizukuStep5")}</li>
              </ol>
            </details>
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("projection")}</span>
              <span className={`status-pill ${projection.channel === "projection" ? "status-granted" : "status-not_granted"}`}>
                {projectionLabel}
              </span>
            </div>
            <p className="settings-card-note">
              {t("projectionNote")}
            </p>
            {projection.error && <p className="settings-error">{projection.error}</p>}
            {projectionMsg && <p className="settings-card-note">{projectionMsg}</p>}
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={askProjection}>{t("grantProjection")}</button>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => { setProjectionMsg(stopProjection()); window.setTimeout(() => setProjection(getProjectionStatus()), 600); }}
              >{t("stopAction")}</button>
              <button type="button" className="btn-ghost" onClick={() => setProjection(getProjectionStatus())}>{t("refreshStatus")}</button>
            </div>
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("health")}</span>
            </div>
            <p className="settings-card-note">
              {t("healthNote")}
            </p>
            {healthMsg && <p className="settings-card-note">{healthMsg}</p>}
            <div className="settings-card-actions">
              <button type="button" className="btn-ghost" onClick={askHealth}>{t("grantSteps")}</button>
            </div>
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("devtools")}</span>
              <span className={`status-pill ${devtools?.enabled ? "status-granted" : "status-not_granted"}`}>
                {devtools?.enabled ? t("on") : t("off")}
              </span>
            </div>
            <p className="settings-card-note">
              {t("devtoolsNote")}
            </p>
            {devtools?.error && <p className="settings-error">{devtools.error}</p>}
            {devtools && devtools.pages.length > 0 && (
              <p className="settings-card-note">
                {t("debugPages")} {devtools.pages.length}：{devtools.pages.slice(0, 4).map((p) => p.title || p.url).join(" · ")}
                {devtools.pages.length > 4 ? " …" : ""}
              </p>
            )}
            <div className="settings-card-actions">
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  const ack = setDevToolsEnabled(!devtools?.enabled);
                  setDevtoolsMsg(ack.message ?? null);
                  window.setTimeout(() => setDevtools(getDevToolsStatus()), 600);
                }}
              >
                {devtools?.enabled ? t("disableDebug") : t("enableDebug")}
              </button>
              <button type="button" className="btn-ghost" onClick={() => setDevtools(getDevToolsStatus())}>{t("refreshStatus")}</button>
            </div>
            {devtoolsMsg && (
              <p className={devtoolsMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{devtoolsMsg}</p>
            )}
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("scriptBackend")}</span><span>{termuxStatus}</span></div>
            <p className="settings-card-note">{t("termuxNote")}</p>
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={() => {
                setTermuxMsg(grantTermuxViaShizuku());
                setTermuxStatus(getTermuxStatus());
              }}>{t("shizukuGrant")}</button>
            </div>
            {termuxMsg && <p className={termuxMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{termuxMsg}</p>}
          </div>

          {error && <div className="settings-error">{error}</div>}

          <div className="settings-note">
            {hasBridge() ? t("bridgeConnected") : t("browserPreview")}
          </div>
        </div>

        <footer className="settings-foot">
          <button type="button" className="btn-ghost" onClick={onClose}>
            {t("cancel")}
          </button>
          <button type="button" className="btn-primary" onClick={save}>
            {t("save")}
          </button>
        </footer>
      </div>
    </div>
  );
}

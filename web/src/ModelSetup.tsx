import { useEffect, useRef, useState } from "react";
import { persistSettings, type Settings } from "./bridge";
import { useI18n } from "./i18n";
import { PROVIDERS, isValidModelUrl, listModels, normalizeModelUrl, providerForUrl, testModel } from "./modelConfig";

interface Props {
  settings: Settings;
  onboarding?: boolean;
  onClose: () => void;
  onSave: (settings: Settings) => void;
}

export function ModelSetup({ settings, onboarding, onClose, onSave }: Props) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(settings);
  const [providerId, setProviderId] = useState(() => providerForUrl(settings.baseUrl).id);
  const [models, setModels] = useState<string[]>([]);
  const [revealKey, setRevealKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [warning, setWarning] = useState("");
  const requestRef = useRef<AbortController | null>(null);
  const provider = PROVIDERS.find(p => p.id === providerId)!;
  const selectedModelListed = models.includes(draft.model);
  useEffect(() => () => requestRef.current?.abort(), []);

  const update = (values: Partial<Settings>) => {
    setDraft(prev => ({ ...prev, ...values })); setError(""); setWarning("");
  };
  const validate = (requireModel: boolean) => {
    if (!isValidModelUrl(draft.baseUrl)) { setError(t("modelUrlInvalid")); return false; }
    if (provider.requiresKey && !draft.apiKey.trim()) { setError(t("modelKeyRequired")); return false; }
    if (requireModel && !draft.model.trim()) { setError(t("modelIdRequired")); return false; }
    return true;
  };
  const normalized = (): Settings => ({ ...draft, baseUrl: normalizeModelUrl(draft.baseUrl), apiKey: draft.apiKey.trim(), model: draft.model.trim() });
  const save = (next: Settings) => {
    try { persistSettings(next); onSave(next); onClose(); }
    catch { setError(t("modelSaveFailed")); }
  };
  const run = async (testAndSave: boolean) => {
    if (busy || !validate(false)) return;
    setBusy(true); setError(""); setWarning("");
    const controller = new AbortController();
    requestRef.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 60_000);
    try {
      const next = normalized();
      if (!testAndSave || !next.model) {
        const ids = await listModels(next, controller.signal);
        if (controller.signal.aborted) return;
        setModels(ids);
        if (!ids.length) { setError(t("modelListEmpty")); return; }
        if (!next.model) {
          next.model = ids[0];
          update({ model: next.model });
        }
      }
      if (testAndSave) {
        const supportsTools = await testModel(next, controller.signal);
        if (controller.signal.aborted) return;
        if (supportsTools) save(next);
        else setWarning(t("modelNoTools"));
      }
    } catch (e) {
      setError(controller.signal.aborted ? t("modelTestTimeout") : `${t("modelTestFailed")} ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      window.clearTimeout(timer);
      requestRef.current = null;
      setBusy(false);
    }
  };

  return <div className="settings-overlay model-setup-overlay" role="dialog" aria-modal="true" aria-labelledby="model-setup-title">
    <section className="settings-panel model-setup-panel">
      <header className="settings-head">
        <div><h2 id="model-setup-title">{t(onboarding ? "connectModel" : "modelSettings")}</h2><p>{t("modelSetupSubtitle")}</p></div>
        <button type="button" className="btn-ghost" onClick={onClose}>{t(onboarding ? "modelLater" : "close")}</button>
      </header>
      <div className="settings-body">
        <div className="model-intro"><span className="empty-mark" aria-hidden="true">A</span><h3>{t(onboarding ? "modelSetupHeading" : "connectModel")}</h3><p>{t("modelSetupNote")}</p></div>
        <label className="field"><span>{t("modelProvider")}</span>
          <select autoFocus value={providerId} disabled={busy} onChange={e => {
            const preset = PROVIDERS.find(p => p.id === e.target.value)!;
            setProviderId(preset.id); setModels([]); setRevealKey(false);
            // Switching providers must not send the old provider's key to a new host.
            update({ baseUrl: preset.baseUrl, apiKey: "", model: "" });
          }}>{PROVIDERS.map(p => <option key={p.id} value={p.id}>{p.name || t("modelCustom")}</option>)}</select>
        </label>
        <label className="field"><span>Base URL</span>
          <input aria-label="Base URL" value={draft.baseUrl} disabled={busy} onChange={e => { setModels([]); update({ baseUrl: e.target.value }); }} placeholder="https://api.example.com/v1" autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          <em>{t("baseUrlHint")}</em>
          {["ollama", "lmstudio"].includes(providerId) && <em>{t("modelLocalHint")}</em>}
        </label>
        <div className="model-key-row">
          <label className="field"><span>{provider.requiresKey ? "API Key" : t("modelOptionalKey")}</span>
            <input type={revealKey ? "text" : "password"} value={draft.apiKey} disabled={busy} onChange={e => update({ apiKey: e.target.value })} autoComplete="off" autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder={t("apiKeyPlaceholder")} />
          </label>
          <button type="button" className="btn-ghost" aria-pressed={revealKey} onClick={() => setRevealKey(v => !v)}>{t(revealKey ? "modelHideKey" : "modelShowKey")}</button>
        </div>
        <label className="field"><span>{t("modelId")}</span>
          <input aria-label={t("modelId")} value={draft.model} list="setup-models" disabled={busy} onChange={e => update({ model: e.target.value })} placeholder={t("modelIdPlaceholder")} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          <datalist id="setup-models">{models.map(id => <option key={id} value={id} />)}</datalist>
          <em>{t("modelManualHint")}</em>
        </label>
        {models.length > 0 && <label className="field"><span>{t("modelListCount", { count: models.length })}</span><select disabled={busy} value={selectedModelListed ? draft.model : ""} onChange={e => update({ model: e.target.value })}>
          <option value="" disabled>{t("modelSelect")}</option>{models.map(id => <option key={id} value={id}>{id}</option>)}
        </select></label>}
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => void run(false)}>{t("modelFetchList")}</button>
        {busy && <p className="settings-card-note" role="status">{t("modelTesting")}</p>}
        {error && <div className="settings-error" role="alert">{error}<p>{t("modelFallbackHint")}</p></div>}
        {warning && <div className="settings-error" role="alert">{warning}</div>}
      </div>
      <footer className="settings-foot model-setup-foot">
        <button type="button" className="btn-primary" disabled={busy} onClick={() => void run(true)}>{t("modelTestSave")}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => { if (validate(true)) save(normalized()); }}>{t("modelSaveDirect")}</button>
      </footer>
    </section>
  </div>;
}

import { useEffect, useRef, useState } from "react";
import { persistSettings, type Settings } from "./bridge";
import { useI18n } from "./i18n";
import { isValidModelUrl, listModels, normalizeModelUrl, providerForUrl, testModel } from "./modelConfig";
import { ModelFields } from "./ModelFields";
import { SettingsHead } from "./SettingsHead";
import { showError, showInfo } from "./toast";

interface Props {
  settings: Settings;
  onboarding?: boolean;
  onClose: () => void;
  onSave: (settings: Settings) => void;
  /** 父级正在播放退场动画。 */
  closing?: boolean;
}

export function ModelSetup({ settings, onboarding, onClose, onSave, closing }: Props) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(settings);
  const [busy, setBusy] = useState(false);
  const requestRef = useRef<AbortController | null>(null);
  const provider = providerForUrl(draft.baseUrl);
  useEffect(() => () => requestRef.current?.abort(), []);

  const update = (values: Partial<Settings>) => {
    setDraft(prev => ({ ...prev, ...values }));
  };
  const validate = (requireModel: boolean) => {
    if (!isValidModelUrl(draft.baseUrl)) { showError(t("modelUrlInvalid")); return false; }
    if (provider.requiresKey && !draft.apiKey.trim()) { showError(t("modelKeyRequired")); return false; }
    if (requireModel && !draft.model.trim()) { showError(t("modelIdRequired")); return false; }
    return true;
  };
  const normalized = (): Settings => ({ ...draft, baseUrl: normalizeModelUrl(draft.baseUrl), apiKey: draft.apiKey.trim(), model: draft.model.trim() });
  const save = (next: Settings) => {
    try { persistSettings(next); onSave(next); onClose(); }
    catch { showError(t("modelSaveFailed")); }
  };
  const run = async (testAndSave: boolean) => {
    if (busy || !validate(false)) return;
    setBusy(true);
    const controller = new AbortController();
    requestRef.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 60_000);
    try {
      const next = normalized();
      if (!testAndSave || !next.model) {
        const ids = await listModels(next, controller.signal);
        if (controller.signal.aborted) return;
        if (!ids.length) { showError(t("modelListEmpty")); return; }
        if (!next.model) {
          next.model = ids[0];
          update({ model: next.model });
        }
      }
      if (testAndSave) {
        const supportsTools = await testModel(next, controller.signal);
        if (controller.signal.aborted) return;
        if (supportsTools) save(next);
        else showInfo(t("modelNoTools"));
      }
    } catch (e) {
      const reason = controller.signal.aborted ? t("modelTestTimeout") : `${t("modelTestFailed")} ${e instanceof Error ? e.message : String(e)}`;
      showError(`${reason}\n${t("modelFallbackHint")}`);
    } finally {
      window.clearTimeout(timer);
      requestRef.current = null;
      setBusy(false);
    }
  };

  return <div className={`settings-overlay model-setup-overlay${closing ? " closing" : ""}`} role="dialog" aria-modal="true" aria-labelledby="model-setup-title">
    <section className="settings-panel model-setup-panel">
      <SettingsHead
        id="model-setup-title"
        title={t(onboarding ? "connectModel" : "modelSettings")}
        backLabel={t(onboarding ? "modelLater" : "back")}
        onBack={onClose}
      />
      <div className="settings-body">
        <div className="model-intro"><h3>{t(onboarding ? "modelSetupHeading" : "connectModel")}</h3><p>{t("modelSetupNote")}</p></div>
        <ModelFields settings={draft} onChange={update} disabled={busy} />
        {busy && <p className="settings-card-note" role="status">{t("modelTesting")}</p>}
      </div>
      <footer className="settings-foot model-setup-foot">
        <button type="button" className="btn-primary" disabled={busy} onClick={() => void run(true)}>{t("modelTestSave")}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => { if (validate(true)) save(normalized()); }}>{t("modelSaveDirect")}</button>
      </footer>
    </section>
  </div>;
}

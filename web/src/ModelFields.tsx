import { useId, useRef, useState } from "react";
import type { Settings } from "./bridge";
import { useI18n } from "./i18n";
import {
  PROVIDER_GROUPS, PROVIDERS, isValidModelUrl, listModels, normalizeModelUrl, providerForUrl,
  type ProviderPreset,
} from "./modelConfig";
import { showError } from "./toast";
import { Icon } from "./icons";

/**
 * 模型连接表单（服务商选择 + Base URL + API Key + 模型 ID + 拉取模型列表）。
 *
 * 首次启动向导（`ModelSetup`）和设置里的"模型配置"页共用这一份 UI，避免两处
 * 各写一套控件、改一处漏一处。测试连接 / 保存等动作由调用方负责。
 */
interface Props {
  /** 当前草稿值（只读使用，修改通过 onChange 回传）。 */
  settings: Settings;
  onChange: (patch: Partial<Settings>) => void;
  /** 外部流程（测试连接 / 保存）进行中时禁用整块表单。 */
  disabled?: boolean;
}

/** Tile monogram: explicit preset mark, else capitals from the brand name, else first two chars. */
export function providerMark(preset: ProviderPreset): string {
  if (preset.mark) return preset.mark;
  const caps = (preset.name.match(/[A-Z]/g) ?? []).join("");
  if (caps.length >= 2) return caps.slice(0, 2);
  const latin = (preset.name.match(/[A-Za-z]/g) ?? []).join("");
  return (latin || preset.name).slice(0, 2).toUpperCase();
}

export function ModelFields({ settings, onChange, disabled }: Props) {
  const { t } = useI18n();
  const provider = providerForUrl(settings.baseUrl);
  const [models, setModels] = useState<string[]>([]);
  const [revealKey, setRevealKey] = useState(false);
  const [fetching, setFetching] = useState(false);
  const listId = useId();
  const modelInputRef = useRef<HTMLInputElement>(null);
  const busy = !!disabled || fetching;

  const fetchModels = async () => {
    if (busy) return;
    if (!isValidModelUrl(settings.baseUrl)) { showError(t("modelUrlInvalid")); return; }
    if (provider.requiresKey && !settings.apiKey.trim()) { showError(t("modelKeyRequired")); return; }
    setFetching(true);
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 60_000);
    try {
      const ids = await listModels(
        { ...settings, baseUrl: normalizeModelUrl(settings.baseUrl), apiKey: settings.apiKey.trim() },
        controller.signal,
      );
      if (controller.signal.aborted) return;
      setModels(ids);
      if (!ids.length) { showError(t("modelListEmpty")); return; }
      if (!settings.model.trim()) onChange({ model: ids[0] });
    } catch (e) {
      showError(controller.signal.aborted ? t("modelTestTimeout") : `${t("modelTestFailed")} ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      window.clearTimeout(timer);
      setFetching(false);
    }
  };

  return (
    <>
      <div className="field provider-field">
        <span id="provider-label">{t("modelProvider")}</span>
        <div className="provider-groups" role="radiogroup" aria-labelledby="provider-label">
          {PROVIDER_GROUPS.map(group => (
            <div key={group.id} className="provider-group">
              <span className="provider-group-label">{t(group.label)}</span>
              <div className="provider-grid">
                {PROVIDERS.filter(p => p.group === group.id).map(p => {
                  const selected = p.id === provider.id;
                  return (
                    <button
                      key={p.id}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      aria-label={p.name || t("modelCustom")}
                      className={`provider-tile${selected ? " selected" : ""}`}
                      title={p.baseUrl || t("modelCustomHint")}
                      disabled={busy}
                      onClick={() => {
                        setModels([]); setRevealKey(false);
                        // Switching providers must not send the old provider's key to a new host.
                        onChange({ baseUrl: p.baseUrl, apiKey: "", model: "" });
                      }}
                    >
                      <span className="provider-mark" aria-hidden="true">{providerMark(p)}</span>
                      <span className="provider-name">{p.name || t("modelCustom")}</span>
                      {selected && <span className="provider-check" aria-hidden="true"><Icon name="check" size={12} strokeWidth={2.6} /></span>}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
      <label className="field"><span>Base URL</span>
        <input
          aria-label="Base URL"
          value={settings.baseUrl}
          disabled={busy}
          onChange={e => { setModels([]); onChange({ baseUrl: e.target.value }); }}
          placeholder="https://api.example.com/v1"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
        <em>{t("baseUrlHint")}</em>
        {["ollama", "lmstudio"].includes(provider.id) && <em>{t("modelLocalHint")}</em>}
      </label>
      <div className="model-key-row">
        <label className="field"><span>{provider.requiresKey ? "API Key" : t("modelOptionalKey")}</span>
          <input
            type={revealKey ? "text" : "password"}
            value={settings.apiKey}
            disabled={busy}
            onChange={e => onChange({ apiKey: e.target.value })}
            autoComplete="off"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder={t("apiKeyPlaceholder")}
          />
        </label>
        <button type="button" className="btn-ghost" aria-pressed={revealKey} onClick={() => setRevealKey(v => !v)}>
          {t(revealKey ? "modelHideKey" : "modelShowKey")}
        </button>
      </div>
      <label className="field"><span>{t("modelId")}</span>
        <input
          ref={modelInputRef}
          aria-label={t("modelId")}
          value={settings.model}
          list={listId}
          disabled={busy}
          onChange={e => onChange({ model: e.target.value })}
          placeholder={t("modelIdPlaceholder")}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
        <datalist id={listId}>{models.map(id => <option key={id} value={id} />)}</datalist>
        <em>{t("modelManualHint")}</em>
      </label>
      <button type="button" className="btn-ghost" disabled={busy} onClick={() => void fetchModels()}>{t("modelFetchList")}</button>
      {models.length > 0 && (
        <div className="field model-picker">
          <span>{t("modelListCount", { count: models.length })}</span>
          <div className="model-options" role="group" aria-label={t("modelSelect")}>
            {models.map(id => (
              <button
                key={id}
                type="button"
                aria-pressed={settings.model === id}
                className={`model-option${settings.model === id ? " selected" : ""}`}
                disabled={busy}
                onClick={() => onChange({ model: id })}
              >{id}</button>
            ))}
            {/* 列表里没有想要的模型时，回到上面的输入框手填。 */}
            <button
              type="button"
              className="model-option model-option-custom"
              disabled={busy}
              onClick={() => { onChange({ model: "" }); modelInputRef.current?.focus(); }}
            >＋ {t("modelCustomEntry")}</button>
          </div>
        </div>
      )}
      {fetching && <p className="settings-card-note" role="status">{t("modelTesting")}</p>}
    </>
  );
}

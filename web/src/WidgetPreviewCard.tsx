import { currentLanguage, translate, useI18n } from "./i18n";
import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from "react";
import { execTool, hasBridge, widgetSave } from "./bridge";
import { showError, showResult } from "./toast";

/**
 * ```widget fence: an in-conversation WYSIWYG preview of a WidgetSpec v2.
 *
 * Rendering is an HTML approximation of the Kotlin RemoteViews renderer
 * (RemoteViews cannot be rendered offscreen without AppWidgetManager): the
 * same node tree, defaults, bindings and footer; widths are simulated for a
 * 4-column home-screen slot. Data sources fetch live values where the
 * conversation tools can provide them (SYSTEM via battery_detail / steps_today
 * / device_info, SCRIPT via js_eval with the same refresh(ctx) wrapper the
 * widget runtime uses); HTTP sources show sample/fallback values only.
 */

type NodeType = "COLUMN" | "ROW" | "TEXT" | "SPACER" | "PROGRESS";
type Binding = "LITERAL" | "DATE" | "BATTERY" | "COUNTDOWN" | "DATA";
type SourceType = "STATIC" | "HTTP" | "SCRIPT" | "SYSTEM";

interface WNode {
  type: NodeType;
  text: string;
  textSize: number;
  maxLines: number;
  color: string;
  padding: number;
  size: number;
  value: number;
  binding: Binding;
  targetDate: string | null;
  source: string | null;
  field: string | null;
  fallback: string | null;
  children: WNode[];
}

interface SourceSpec {
  type: SourceType;
  value: Record<string, string>;
  url: string | null;
  script: string | null;
  system: string | null;
  sample: Record<string, string>;
  fallback: Record<string, string>;
}

interface Spec {
  schemaVersion: 2;
  name: string;
  background: string;
  padding: number;
  root: WNode;
  data: { sources: Record<string, SourceSpec> } | null;
}

const COLOR_RE = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/;
const SOURCE_KEY_RE = /^[a-z][a-z0-9_]{0,31}$/;
const FIELD_KEY_RE = /^[A-Za-z][A-Za-z0-9_]{0,31}$/;
const CLICK_URI_RE = /^[A-Za-z][A-Za-z0-9+.\-]{0,31}:\S+$/;
const BLOCKED_SCHEMES = new Set([
  "file", "content", "javascript", "data", "blob", "intent", "android.resource", "jar",
]);
const SYSTEM_PROVIDERS = new Set([
  "BATTERY", "CLOCK", "NETWORK", "LOCALE", "STORAGE", "DEVICE_INFO", "BATTERY_DETAIL", "STEPS_TODAY",
]);

function fail(message: string): never {
  throw new Error(message);
}

function asInt(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) return Math.round(value);
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value))) {
    return Math.round(Number(value));
  }
  return fallback;
}

function asStr(value: unknown, fallback: string | null = null): string | null {
  return typeof value === "string" ? value : fallback;
}

function asFields(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (v === null || v === undefined || typeof v === "object") continue;
    out[k] = String(v);
  }
  return out;
}

function normNode(raw: unknown, spec: Spec, depth: number, path: string): WNode {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) fail(`${path} ${translate("widgetNodeObject")}`);
  const r = raw as Record<string, unknown>;
  const type = String(r.type ?? "").toUpperCase() as NodeType;
  if (!["COLUMN", "ROW", "TEXT", "SPACER", "PROGRESS"].includes(type)) {
    fail(`${path} ${translate("widgetNodeTypeInvalid")}：${String(r.type ?? translate("widgetUnspecified"))}`);
  }
  const container = type === "COLUMN" || type === "ROW";
  const node: WNode = {
    type,
    text: typeof r.text === "string" ? r.text : "",
    textSize: asInt(r.textSize, 18),
    maxLines: asInt(r.maxLines, 3),
    color: asStr(r.color, "#FFFFFF") ?? "#FFFFFF",
    padding: asInt(r.padding, 0),
    size: asInt(r.size, 8),
    value: asInt(r.value, 50),
    binding: (String(r.binding ?? "LITERAL").toUpperCase() as Binding),
    targetDate: asStr(r.targetDate),
    source: asStr(r.source),
    field: asStr(r.field),
    fallback: asStr(r.fallback),
    children: [],
  };
  if (node.text.length > 400) fail(translate("widgetTextTooLong"));
  if (node.maxLines < 1 || node.maxLines > 6) fail(translate("widgetMaxLines"));
  if (node.textSize < 10 || node.textSize > 48) fail(translate("widgetFontSize"));
  if (!COLOR_RE.test(node.color)) fail(translate("widgetTextColor"));
  if (node.padding < 0 || node.padding > 24) fail(translate("widgetNodePadding"));
  if (node.size < 0 || node.size > 64) fail(translate("widgetSpacing"));
  if (node.value < 0 || node.value > 100) fail(translate("widgetProgressRange"));
  if (!["LITERAL", "DATE", "BATTERY", "COUNTDOWN", "DATA"].includes(node.binding)) {
    fail(`${path} ${translate("widgetBindingInvalid")}：${String(r.binding ?? "")}`);
  }
  const rawChildren = Array.isArray(r.children) ? r.children : [];
  if (!container && rawChildren.length > 0) fail(translate("widgetOnlyLayoutChildren"));
  if (container && node.binding !== "LITERAL") fail(translate("widgetLayoutNoBinding"));
  if (type === "SPACER" && node.binding !== "LITERAL") fail(translate("widgetSpacerNoBinding"));
  if (type === "PROGRESS" && !["LITERAL", "BATTERY", "DATA"].includes(node.binding)) {
    fail(translate("widgetProgressBinding"));
  }
  if (node.binding === "COUNTDOWN") {
    if (!node.targetDate) fail(translate("widgetCountdownTarget"));
    if (!/^\d{4}-\d{2}-\d{2}$/.test(node.targetDate) || !Number.isFinite(Date.parse(`${node.targetDate}T00:00:00`))) {
      fail(translate("widgetDateFormat"));
    }
  }
  if (node.binding === "DATA") {
    if (type !== "TEXT" && type !== "PROGRESS") fail(translate("widgetDataNode"));
    if (!node.source) fail(translate("widgetSourceRequired"));
    if (!node.field) fail(translate("widgetFieldRequired"));
    if (!SOURCE_KEY_RE.test(node.source)) fail(translate("widgetSourceNameInvalid"));
    if (!FIELD_KEY_RE.test(node.field)) fail(translate("widgetFieldInvalid"));
    if (!spec.data?.sources[node.source]) fail(`${translate("widgetSource")} ${node.source} ${translate("widgetUndefined")}`);
    if (node.fallback !== null && node.fallback.length > 400) fail(translate("widgetFallbackTooLong"));
  } else {
    if (node.source !== null || node.field !== null) fail(translate("widgetSourceOnlyData"));
    if (node.fallback !== null) fail(translate("widgetFallbackOnlyData"));
  }
  const click = asStr(r.click);
  if (click !== null) {
    if (click.length > 2048) fail(translate("widgetClickTooLong"));
    if (!CLICK_URI_RE.test(click)) fail(translate("widgetClickFormat"));
    if (BLOCKED_SCHEMES.has(click.substring(0, click.indexOf(":")).toLowerCase())) {
      fail(`${translate("widgetUnsupportedLink")} ${click.substring(0, click.indexOf(":")).toLowerCase()}:`);
    }
  }
  node.children = rawChildren.map((child, i) => normNode(child, spec, depth + 1, `${path}.children[${i}]`));
  return node;
}

function normalize(code: string): Spec {
  let parsed: unknown;
  try {
    parsed = JSON.parse(code);
  } catch (e) {
    fail(`${translate("widgetJsonParseFailed")}：${e instanceof Error ? e.message : String(e)}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) fail(translate("widgetObjectRequired"));
  const r = parsed as Record<string, unknown>;
  const version = asInt(r.schemaVersion, 2);
  if (version !== 1 && version !== 2) fail(translate("widgetVersionUnsupported"));
  const name = typeof r.name === "string" ? r.name : "";
  if (!name.trim() || name.length > 60) fail(translate("widgetNameLength"));
  const background = asStr(r.background, "#17212C") ?? "#17212C";
  if (!COLOR_RE.test(background)) fail(translate("widgetBackgroundFormat"));
  const padding = asInt(r.padding, 16);
  if (padding < 0 || padding > 32) fail(translate("widgetPadding"));

  const spec: Spec = {
    schemaVersion: 2, // v1 is migrated on read, like SpecCodec.migrate
    name,
    background,
    padding,
    root: null as unknown as WNode, // replaced below, before any use
    data: null,
  };

  const rawSources = r.data && typeof r.data === "object" && !Array.isArray(r.data)
    ? (r.data as Record<string, unknown>).sources
    : undefined;
  let sources: Record<string, SourceSpec> = {};
  if (rawSources !== undefined && rawSources !== null) {
    if (typeof rawSources !== "object" || Array.isArray(rawSources)) fail(translate("widgetSourcesObject"));
    const entries = Object.entries(rawSources as Record<string, unknown>);
    if (entries.length === 0) fail(translate("widgetSourcesEmpty"));
    if (entries.length > 8) fail(translate("widgetSourcesLimit"));
    sources = {};
    for (const [key, raw] of entries) {
      if (!SOURCE_KEY_RE.test(key)) fail(translate("widgetSourceKeyFormat"));
      if (!raw || typeof raw !== "object" || Array.isArray(raw)) fail(`${translate("widgetSource")} ${key} ${translate("widgetNotObject")}`);
      const s = raw as Record<string, unknown>;
      const type = String(s.type ?? "").toUpperCase() as SourceType;
      if (!["STATIC", "HTTP", "SCRIPT", "SYSTEM"].includes(type)) fail(`${translate("widgetSource")} ${key} ${translate("widgetTypeInvalid")}：${String(s.type ?? translate("widgetUnspecified"))}`);
      const source: SourceSpec = {
        type,
        value: asFields(s.value),
        url: asStr(s.url),
        script: asStr(s.script),
        system: asStr(s.system),
        sample: asFields(s.sample),
        fallback: asFields(s.fallback),
      };
      if (type === "STATIC") {
        if (Object.keys(source.value).length === 0) fail(translate("widgetStaticValue"));
        if (source.url !== null || source.script !== null) fail(translate("widgetStaticFields"));
      } else if (type === "HTTP") {
        if (!source.url) fail(translate("widgetHttpUrl"));
        if (!source.url.startsWith("https://") || source.url.length > 2048 || /\s/.test(source.url)) {
          fail(translate("widgetHttpsOnly"));
        }
      } else if (type === "SCRIPT") {
        if (!source.script || !source.script.trim()) fail(translate("widgetScriptRequired"));
        if (source.script.length > 16384) fail(translate("widgetScriptLength"));
      } else if (type === "SYSTEM") {
        if (!source.system || !SYSTEM_PROVIDERS.has(source.system)) {
          fail(translate("widgetSystemRequired"));
        }
      }
      sources[key] = source;
    }
    spec.data = { sources };
  }

  const root = normNode(r.root ?? fail(translate("widgetRootMissing")), spec, 1, "root");
  if (root.type !== "COLUMN" && root.type !== "ROW") fail(translate("widgetRootLayout"));
  let count = 0;
  const visit = (node: WNode, depth: number) => {
    if (++count > 40) fail(translate("widgetNodeLimit"));
    if (depth > 6) fail(translate("widgetDepthLimit"));
    node.children.forEach((child) => visit(child, depth + 1));
  };
  visit(root, 1);
  spec.root = root;
  return spec;
}

/** Canonical JSON for save/pin: normalized tree, unknown fields dropped. */
function canonicalJson(spec: Spec): string {
  const node = (n: WNode): Record<string, unknown> => ({
    type: n.type,
    text: n.text,
    textSize: n.textSize,
    maxLines: n.maxLines,
    color: n.color,
    padding: n.padding,
    size: n.size,
    value: n.value,
    binding: n.binding,
    ...(n.targetDate !== null ? { targetDate: n.targetDate } : {}),
    ...(n.source !== null ? { source: n.source } : {}),
    ...(n.field !== null ? { field: n.field } : {}),
    ...(n.fallback !== null ? { fallback: n.fallback } : {}),
    children: n.children.map(node),
  });
  const source = (s: SourceSpec): Record<string, unknown> => ({
    type: s.type,
    ...(Object.keys(s.value).length ? { value: s.value } : {}),
    ...(s.url !== null ? { url: s.url } : {}),
    ...(s.script !== null ? { script: s.script } : {}),
    ...(s.system !== null ? { system: s.system } : {}),
    ...(Object.keys(s.sample).length ? { sample: s.sample } : {}),
    ...(Object.keys(s.fallback).length ? { fallback: s.fallback } : {}),
  });
  const out: Record<string, unknown> = {
    schemaVersion: spec.schemaVersion,
    name: spec.name,
    background: spec.background,
    padding: spec.padding,
    root: node(spec.root),
    ...(spec.data
      ? { data: { sources: Object.fromEntries(Object.entries(spec.data.sources).map(([k, v]) => [k, source(v)])) } }
      : {}),
  };
  return JSON.stringify(out);
}

/** Android #AARRGGBB → CSS #RRGGBBAA (both accepted on input). */
function cssColor(color: string): string {
  const m = COLOR_RE.exec(color);
  if (!m) return color;
  if (color.length === 9) return `#${color.slice(3, 9)}${color.slice(1, 3)}`;
  return color;
}

/* ------------------------------------------------------------------ */
/* Data sources                                                        */
/* ------------------------------------------------------------------ */

function flatten(obj: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === null || v === undefined || typeof v === "object") continue;
    out[k] = String(v).slice(0, 400);
  }
  return out;
}

function clockValues(): Record<string, string> {
  const now = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  const offsetMin = -now.getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const abs = Math.abs(offsetMin);
  const iso = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}T${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}${sign}${p(Math.floor(abs / 60))}:${p(abs % 60)}`;
  return {
    now: iso,
    date: `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`,
    time: `${p(now.getHours())}:${p(now.getMinutes())}`,
    weekday: new Intl.DateTimeFormat(currentLanguage() === "zh" ? "zh-CN" : "en-US", { weekday: "short" }).format(now),
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    epochSeconds: String(Math.floor(now.getTime() / 1000)),
  };
}

function localeValues(): Record<string, string> {
  const values: Record<string, string> = {};
  const tag = navigator.language || "zh-CN";
  const [language, region] = tag.split("-");
  values.language = language;
  if (region) values.region = region;
  values.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return values;
}

function networkValues(): Record<string, string> {
  return { connected: String(navigator.onLine) };
}

async function execFlat(name: string): Promise<Record<string, string>> {
  const result = await execTool(name, {});
  if (!result.ok || result.result.startsWith("ERROR:")) throw new Error(result.result);
  const parsed: unknown = JSON.parse(result.result);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`${name} ${translate("widgetNoJsonObject")}`);
  return flatten(parsed as Record<string, unknown>);
}

async function systemValues(system: string): Promise<Record<string, string>> {
  switch (system) {
    case "BATTERY":
    case "BATTERY_DETAIL": {
      const flat = await execFlat("battery_detail");
      if (system === "BATTERY") {
        return {
          percent: flat.percent ?? "",
          charging: flat.charging ?? "",
          status: flat.status ?? "",
        };
      }
      return flat;
    }
    case "STEPS_TODAY":
      return execFlat("steps_today");
    case "DEVICE_INFO": {
      // Mirrors SystemSources: a single "text" field with the raw tool output.
      const result = await execTool("device_info", {});
      if (!result.ok || result.result.startsWith("ERROR:")) throw new Error(result.result);
      return { text: result.result };
    }
    case "CLOCK":
      return clockValues();
    case "LOCALE":
      return localeValues();
    case "NETWORK":
      return networkValues();
    default:
      return {}; // STORAGE has no web equivalent → sample/fallback takes over
  }
}

async function scriptValues(
  source: SourceSpec,
  battery: number | null,
  collected: Record<string, Record<string, string>>,
): Promise<Record<string, string>> {
  const nested = (values: Record<string, Record<string, string>>) =>
    Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v]));
  const ctx = { now: new Date().toISOString(), battery, sources: nested(collected), cache: nested(collected) };
  // Same wrapper WidgetDataRefresher.runScript uses: define refresh, call it with ctx.
  const program = `${source.script ?? ""}
;(function () {
  var ctx = JSON.parse(${JSON.stringify(JSON.stringify(ctx))});
  var out = (typeof refresh === 'function') ? refresh(ctx) : null;
  return JSON.stringify(out === null || out === undefined ? {} : out);
})()`;
  const result = await execTool("js_eval", { script: program });
  if (!result.ok || result.result.startsWith("ERROR:")) throw new Error(result.result);
  // js_eval JSON.stringify's the last expression → our returned string is encoded once more.
  const inner: unknown = JSON.parse(result.result);
  if (typeof inner !== "string") throw new Error(translate("widgetScriptObject"));
  const decoded: unknown = JSON.parse(inner);
  if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) throw new Error(translate("widgetScriptObject"));
  return flatten(decoded as Record<string, unknown>);
}

/* ------------------------------------------------------------------ */
/* Bindings (mirror of the Kotlin Bindings object)                     */
/* ------------------------------------------------------------------ */

function bindText(node: WNode, battery: number | null, sources: Record<string, Record<string, string>>): string {
  switch (node.binding) {
    case "DATE": {
      const now = new Date();
      const weekday = new Intl.DateTimeFormat(currentLanguage() === "zh" ? "zh-CN" : "en-US", { weekday: "long" }).format(now);
      return node.text + `${now.getMonth() + 1}${translate("widgetMonth")}${now.getDate()}${translate("widgetDay")} ${weekday}`;
    }
    case "BATTERY":
      return node.text + (battery !== null ? `${battery}%` : translate("widgetBatteryUnknown"));
    case "COUNTDOWN": {
      if (!node.targetDate) return node.text;
      const target = Date.parse(`${node.targetDate}T00:00:00`);
      if (!Number.isFinite(target)) return node.text;
      const today = new Date();
      const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
      const days = Math.round((target - midnight) / 86_400_000);
      if (days > 0) return node.text + `${translate("widgetDaysLeft")} ${days} ${translate("widgetDays")}`;
      if (days === 0) return node.text + translate("widgetCountdownToday");
      return node.text + translate("widgetDaysAgo", { count: -days });
    }
    case "DATA":
      return node.text + (sources[node.source ?? ""]?.[node.field ?? ""] ?? node.fallback ?? "");
    default:
      return node.text;
  }
}

function bindProgress(node: WNode, battery: number | null, sources: Record<string, Record<string, string>>): number {
  if (node.binding === "BATTERY") return Math.max(0, Math.min(100, battery ?? 0));
  if (node.binding === "DATA") {
    const raw = sources[node.source ?? ""]?.[node.field ?? ""];
    const v = raw !== undefined ? Number(raw) : NaN;
    return Number.isFinite(v) ? Math.max(0, Math.min(100, Math.round(v))) : node.value;
  }
  return node.value;
}

/* ------------------------------------------------------------------ */
/* Node rendering                                                      */
/* ------------------------------------------------------------------ */

function NodeView({
  node,
  battery,
  sources,
}: {
  node: WNode;
  battery: number | null;
  sources: Record<string, Record<string, string>>;
}): ReactNode {
  const pad = node.padding;
  switch (node.type) {
    case "COLUMN":
      return (
        <div className="wp-column" style={{ padding: pad }}>
          {node.children.map((child, i) => (
            <NodeView key={i} node={child} battery={battery} sources={sources} />
          ))}
        </div>
      );
    case "ROW":
      return (
        <div className="wp-row" style={{ padding: pad }}>
          {node.children.map((child, i) =>
            child.type === "SPACER" ? (
              <NodeView key={i} node={child} battery={battery} sources={sources} />
            ) : (
              // The RemoteViews renderer gives every non-spacer ROW child an
              // equal share of the inner width; flex-basis 0 mirrors that.
              <div key={i} className="wp-cell">
                <NodeView node={child} battery={battery} sources={sources} />
              </div>
            ),
          )}
        </div>
      );
    case "TEXT": {
      const style: CSSProperties = {
        padding: 0,
        fontSize: node.textSize,
        color: node.color,
        WebkitLineClamp: node.maxLines,
      };
      return (
        <div className="wp-text" style={pad ? { ...style, padding: pad } : style}>
          {bindText(node, battery, sources)}
        </div>
      );
    }
    case "SPACER":
      return <div className="wp-spacer" style={{ width: node.size, height: node.size, padding: pad }} />;
    case "PROGRESS": {
      const value = bindProgress(node, battery, sources);
      return (
        <div
          className="wp-progress"
          style={{ padding: pad }}
          role="progressbar"
          aria-valuenow={value}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div className="wp-progress-fill" style={{ width: `${value}%` }} />
        </div>
      );
    }
  }
}

/* ------------------------------------------------------------------ */
/* Card                                                                */
/* ------------------------------------------------------------------ */

export function WidgetPreviewCard({ code }: { code: string }) {
  const { t, language } = useI18n();
  const parsed = useMemo(() => {
    try {
      const spec = normalize(code);
      return { spec, canonical: canonicalJson(spec), error: null as string | null };
    } catch (e) {
      return { spec: null, canonical: "", error: e instanceof Error ? e.message : String(e) };
    }
  }, [code, language]);

  const [sources, setSources] = useState<Record<string, Record<string, string>>>({});
  const [battery, setBattery] = useState<number | null>(null);
  const [busy, setBusy] = useState<"save" | "pin" | null>(null);

  const spec = parsed.spec;
  const needsBattery = useMemo(() => {
    if (!spec) return false;
    let need = false;
    const visit = (n: WNode) => {
      if (n.binding === "BATTERY") need = true;
      if (n.binding === "DATA" && n.source && spec.data?.sources[n.source]?.system === "BATTERY") need = true;
      n.children.forEach(visit);
    };
    visit(spec.root);
    return need;
  }, [spec]);

  // Resolve data sources once per spec (live values where available).
  useEffect(() => {
    if (!spec) return;
    let cancelled = false;
    const data = spec.data?.sources ?? {};
    (async () => {
      let batteryValue: number | null = null;
      if (needsBattery || Object.values(data).some((s) => s.system === "BATTERY" || s.system === "BATTERY_DETAIL")) {
        try {
          const result = await execTool("battery_detail", {});
          if (result.ok && !result.result.startsWith("ERROR:")) {
            const percent = (JSON.parse(result.result) as { percent?: unknown }).percent;
            const n = Number(percent);
            batteryValue = Number.isFinite(n) ? Math.round(n) : null;
          }
        } catch {
          batteryValue = null;
        }
      }
      const collected: Record<string, Record<string, string>> = {};
      for (const [key, source] of Object.entries(data)) {
        if (cancelled) return;
        if (source.type === "HTTP") continue; // placeholder note covers it
        let values: Record<string, string> | null = null;
        try {
          if (source.type === "STATIC") values = source.value;
          else if (source.type === "SYSTEM") values = await systemValues(source.system ?? "");
          else if (source.type === "SCRIPT") values = await scriptValues(source, batteryValue, collected);
        } catch {
          values = null;
        }
        const picked = values && Object.keys(values).length > 0
          ? values
          : Object.keys(source.sample).length > 0
            ? source.sample
            : Object.keys(source.fallback).length > 0
              ? source.fallback
              : null;
        if (picked) collected[key] = picked;
      }
      if (!cancelled) {
        setSources(collected);
        setBattery(batteryValue);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [spec, needsBattery]);

  const hasHttp = useMemo(
    () => !!spec && Object.values(spec.data?.sources ?? {}).some((s) => s.type === "HTTP"),
    [spec],
  );

  if (parsed.error || !spec) {
    return (
      <div className="widget-preview-card">
        <div className="chart-head">
          <span className="chart-badge">{t("widgetPreview")}</span>
          <span className="chart-error-text">{parsed.error ?? t("invalidConfig")}</span>
        </div>
      </div>
    );
  }

  const onSave = () => {
    setBusy("save");
    try {
      // Same Kotlin WidgetApi.save channel the settings editor uses. (The
      // widget_generate execTool name is intercepted for the LLM flow, so it
      // cannot perform a real save from JS.)
      const out = widgetSave(parsed.canonical);
      showResult(out.startsWith("ERROR:") ? out : `${t("savedWidget")} (id=${out})`);
    } catch (e) {
      showError(`ERROR: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(null);
    }
  };

  const onPin = async () => {
    setBusy("pin");
    const result = await execTool("widget_save_and_pin", { spec_json: parsed.canonical });
    showResult(result.result);
    setBusy(null);
  };

  const now = new Date();
  const updated = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;

  return (
    <div className="widget-preview-card">
      <div className="chart-head">
        <span className="chart-badge">{t("widgetPreview")}</span>
        <span className="wp-name" title={spec.name}>{spec.name}</span>
      </div>
      <div className="wp-surface-wrap">
        <div className="wp-surface" style={{ background: cssColor(spec.background), padding: spec.padding }}>
          <NodeView node={spec.root} battery={battery} sources={sources} />
          <div className="wp-footer">
            <span className="wp-status">{t("updated")} {updated}</span>
            <span className="wp-refresh" aria-hidden="true">↻</span>
          </div>
        </div>
      </div>
      {(hasHttp || !hasBridge()) && (
        <div className="wp-note">
          {hasHttp && <div>{t("widgetHttpNote")}</div>}
          {!hasBridge() && <div>{t("widgetBrowserNote")}</div>}
        </div>
      )}
      <div className="wp-actions">
        <button type="button" onClick={onSave} disabled={busy !== null}>
          {busy === "save" ? t("saving") : t("saveWidget")}
        </button>
        <button type="button" className="wp-pin" onClick={() => void onPin()} disabled={busy !== null}>
          {busy === "pin" ? t("requesting") : t("saveAndPin")}
        </button>
      </div>
    </div>
  );
}

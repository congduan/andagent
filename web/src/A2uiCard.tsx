import { useId, useMemo, useState, type ReactNode } from "react";
import { useI18n } from "./i18n";

/**
 * ```a2ui fence: a declarative, catalog-only form card.
 *
 * The model emits JSON, never code: { title?, submit_label?, fields: [...] }.
 * Only the component types below are rendered; anything else degrades to a
 * read-only label with one neutral warning. All text goes through React text
 * nodes, and the submit event name is fixed so a fence can never dispatch
 * arbitrary events.
 */
type RawNode = Record<string, unknown>;

type ChoiceOption = { value: string; label: string };
type ButtonOption = { value: string; label: string };

const MAX_NODES = 60;
const MAX_DEPTH = 8;
const MAX_LABEL = 200;
const MAX_OPTIONS = 50;
const MAX_CODE = 100 * 1024;
const MAX_PATTERN = 100;
const MAX_REGEX_INPUT = 1000;

const RESERVED_KEYS = new Set(["__proto__", "constructor", "prototype"]);

// Reject patterns with nested/adjacent quantifiers (e.g. `(a+)+`, `a++`,
// `(a|aa)+`) before they reach the main-thread regex engine. Character
// classes are stripped first so `[+*]+` is not misclassified. Any quantified
// group — `(a?){24}` included — is rejected outright: the quantifier budget
// below counts literal quantifiers, but a group quantifier multiplies the
// inner states, so `^(a?){24}$` slips every count-based check while still
// backtracking exponentially. Fail-closed.
const UNSAFE_PATTERN_RE =
  /(?:[+*]|\{\d+(?:,\d*)?\})\s*(?:[+*]|\{\d+(?:,\d*)?\})|\)\s*\)*\s*(?:[+*?]|\{\d+(?:,\d*)?\})/;

// Adjacent quantifiers alone are not enough: `^a*a*a*...b$` has no nested or
// adjacent quantifier at all but still backtracks catastrophically. The only
// bulletproof main-thread policy is a tight static subset — allow at most 2
// quantified atoms in the whole pattern (after class/escape flattening);
// everything more complex fails closed with "格式不符合要求". (2, not 3:
// `^a*a*a*b$` — three star quantifiers over the same char — measured ~300 ms
// on a 1000-char non-matching input, and N such fields on one card multiply
// on the main thread. At 2, the worst construct `^a*a*b$` stays O(n^2) with a
// tiny constant and measured ≤38 ms at 500 chars / unmeasurable at 1000.)
const MAX_QUANTIFIERS = 2;

const KNOWN_TYPES = new Set([
  "TEXT_INPUT",
  "NUMBER_SLIDER",
  "CHOICE",
  "DATE_PICK",
  "SWITCH",
  "BUTTON_GROUP",
  "CARD",
  "ROW",
  "COLUMN",
  "LABEL",
]);

const VALUE_TYPES = new Set([
  "TEXT_INPUT",
  "NUMBER_SLIDER",
  "CHOICE",
  "DATE_PICK",
  "SWITCH",
  "BUTTON_GROUP",
]);

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^\+?[\d\s()-]{6,20}$/;

type ParseResult =
  | { ok: true; title: string; submitLabel: string; fields: RawNode[]; unknown: boolean }
  | { ok: false; reason: "parse" | "fields" | "tooMany" | "tooLarge"; detail: string };

type SliderRange =
  | { ok: true; min: number; max: number; step: number; def: number }
  | { ok: false; min: number; max: number };

type KeyState = { duplicate: boolean; reserved: boolean };

function isRecord(value: unknown): value is RawNode {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function clip(value: string): string {
  return value.length > MAX_LABEL ? value.slice(0, MAX_LABEL) : value;
}

function finiteNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function clampNumber(value: number, min: number, max: number): number {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

function sliderRange(node: RawNode): SliderRange {
  const min = finiteNumber(node.min, 0);
  const max = finiteNumber(node.max, 100);
  if (min > max) return { ok: false, min, max };
  const rawStep = finiteNumber(node.step, 1);
  const step = rawStep > 0 ? rawStep : 1;
  return { ok: true, min, max, step, def: clampNumber(finiteNumber(node.default, min), min, max) };
}

// Snap to the same grid the range input uses, so the submitted value always
// equals the value shown next to the slider.
function sliderValue(range: Extract<SliderRange, { ok: true }>, value: number): number {
  const clamped = clampNumber(value, range.min, range.max);
  const snapped = range.min + Math.round((clamped - range.min) / range.step) * range.step;
  // Trim IEEE-754 noise (0.1 * 3 → 0.30000000000000004) so the number shown
  // next to the slider and the submitted number stay readable; 12 significant
  // digits still covers any realistic step.
  return clampNumber(Number(snapped.toPrecision(12)), range.min, range.max);
}

function isUnsafePattern(pattern: string): boolean {
  // Strip character classes and escape pairs, replacing each with a neutral
  // placeholder: `[+*]+` and `\+\+` are not nested quantifiers, and deleting
  // them outright would falsely fuse neighboring quantifiers (e.g. the common
  // email pattern `^[\w.+-]+@[\w-]+\.[\w.]+$` would look like `++`).
  const flat = pattern
    .replace(/\[(?:\\.|[^\]])*\]/g, "C")
    .replace(/\\./g, "E");
  if (UNSAFE_PATTERN_RE.test(flat)) return true;
  // Quantifier budget: `a*a*a*...b` slips past the adjacency/nesting rules
  // but still backtracks exponentially, so cap total quantified atoms. `?`
  // is a quantifier too (`^a?a?a?...b$` backtracks the same way).
  const quantifiers = flat.match(/[+*?]|(?:\{\d+(?:,\d*)?\})/g);
  return quantifiers !== null && quantifiers.length > MAX_QUANTIFIERS;
}

type RegexCheck = "ok" | "fail" | "invalid";

function checkPattern(pattern: string, value: string): RegexCheck {
  if (!pattern) return "ok";
  if (pattern.length > MAX_PATTERN || value.length > MAX_REGEX_INPUT) return "invalid";
  if (isUnsafePattern(pattern)) return "invalid";
  let compiled: RegExp;
  try {
    compiled = new RegExp(pattern);
  } catch {
    return "invalid";
  }
  try {
    return compiled.test(value) ? "ok" : "fail";
  } catch {
    return "invalid";
  }
}

function inspect(fields: unknown[]): { count: number; unknown: boolean } {
  let count = 0;
  let unknown = false;
  const stack: unknown[] = [...fields];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!isRecord(node)) continue;
    count++;
    if (count > MAX_NODES) return { count, unknown };
    const type = text(node.type);
    if (!KNOWN_TYPES.has(type)) unknown = true;
    if (Array.isArray(node.children)) {
      for (const child of node.children) stack.push(child);
    }
  }
  return { count, unknown };
}

function parseSpec(code: string): ParseResult {
  if (code.length > MAX_CODE) {
    return { ok: false, reason: "tooLarge", detail: `${code.length} > ${MAX_CODE}` };
  }
  let data: unknown;
  try {
    data = JSON.parse(code);
  } catch (error) {
    return {
      ok: false,
      reason: "parse",
      detail: error instanceof Error ? error.message : String(error),
    };
  }
  if (!isRecord(data)) {
    return { ok: false, reason: "fields", detail: "fields must be an array" };
  }
  if (!Array.isArray(data.fields) || data.fields.length === 0) {
    return { ok: false, reason: "fields", detail: "fields must be a non-empty array" };
  }
  const fields = data.fields.filter(isRecord);
  if (fields.length === 0) {
    return { ok: false, reason: "fields", detail: "fields must contain objects" };
  }
  const { count, unknown } = inspect(fields);
  if (count > MAX_NODES) {
    return { ok: false, reason: "tooMany", detail: `${count} > ${MAX_NODES}` };
  }
  return {
    ok: true,
    title: clip(text(data.title)),
    submitLabel: clip(text(data.submit_label)),
    fields,
    unknown,
  };
}

function choiceOptions(value: unknown): ChoiceOption[] {
  if (!Array.isArray(value)) return [];
  const out: ChoiceOption[] = [];
  for (const option of value) {
    if (typeof option === "string") {
      out.push({ value: option, label: clip(option) });
    } else if (isRecord(option) && typeof option.value === "string") {
      out.push({ value: option.value, label: clip(text(option.label) || option.value) });
    }
    if (out.length >= MAX_OPTIONS) break;
  }
  return out;
}

function buttonOptions(value: unknown): ButtonOption[] {
  if (!Array.isArray(value)) return [];
  const out: ButtonOption[] = [];
  for (const option of value) {
    if (typeof option === "string") {
      out.push({ value: option, label: clip(option) });
    } else if (isRecord(option) && typeof option.value === "string") {
      out.push({ value: option.value, label: clip(text(option.label) || option.value) });
    }
    if (out.length >= MAX_OPTIONS) break;
  }
  return out;
}

function walkValues(
  nodes: RawNode[],
  depth: number,
  visit: (node: RawNode, depth: number) => void,
): void {
  if (depth > MAX_DEPTH) return;
  for (const node of nodes) {
    visit(node, depth);
    // Only ROW/COLUMN lay their children out on screen. Descending into
    // children of any other node (CARD, LABEL, a value field...) would let
    // invisible fields participate in key dedup, validation and submit — a
    // required field hidden inside a CARD would make the form un-submittable.
    if (text(node.type) === "ROW" || text(node.type) === "COLUMN") {
      if (Array.isArray(node.children)) {
        walkValues(node.children.filter(isRecord), depth + 1, visit);
      }
    }
  }
}

// A required field that can never produce a usable value — a missing key, a
// degraded config (no usable option / an option whose value is the empty
// string, which valueOf treats as undefined, invalid slider range) — makes the
// whole card broken. Broken cards disable submit and show a bilingual hint
// instead of either silently omitting the field or trapping the user in
// "必填" limbo. Pure config check: callable during render, before interaction.
function requiredIsUnsatisfiable(node: RawNode): boolean {
  if (!text(node.key)) return true;
  const type = text(node.type);
  if (type === "CHOICE" || type === "BUTTON_GROUP") {
    const options = type === "CHOICE" ? choiceOptions(node.options) : buttonOptions(node.buttons);
    // At least one option must have a non-empty value; "" would be read back
    // as undefined by valueOf, so an all-empty list can never be satisfied.
    return !options.some((option) => Boolean(option.value));
  }
  if (type === "NUMBER_SLIDER") return !sliderRange(node).ok;
  return false;
}

export function A2uiCard({ code }: { code: string }) {
  const { t } = useI18n();
  const reactId = useId().replace(/[^a-zA-Z0-9]/g, "");
  const spec = useMemo(() => parseSpec(code), [code]);
  const keyPlan = useMemo(() => {
    const plan = new Map<RawNode, KeyState>();
    if (!spec.ok) return plan;
    const seen = new Set<string>();
    walkValues(spec.fields, 1, (node) => {
      if (!VALUE_TYPES.has(text(node.type))) return;
      const key = text(node.key);
      if (!key) return;
      if (RESERVED_KEYS.has(key)) {
        plan.set(node, { duplicate: false, reserved: true });
        return;
      }
      if (seen.has(key)) {
        plan.set(node, { duplicate: true, reserved: false });
      } else {
        seen.add(key);
        plan.set(node, { duplicate: false, reserved: false });
      }
    });
    return plan;
  }, [spec]);
  // Prototype-less: a field keyed "toString"/"valueOf"/"constructor" (only
  // "constructor"/"prototype"/"__proto__" are rejected as reserved) must read
  // back as undefined, not as an inherited function.
  const [values, setValues] = useState<Record<string, unknown>>(() => Object.create(null));
  const [errors, setErrors] = useState<Record<string, string>>(() => Object.create(null));
  const [submitted, setSubmitted] = useState<string | null>(null);
  // 折叠状态必须在所有条件 return 之前声明（React hooks 顺序恒定）。
  const [collapsed, setCollapsed] = useState(false);
  // Broken cards are detected from config alone at parse time: the submit
  // button is disabled (and the hint shown) from the very first render,
  // not only after a failed submit attempt.
  const broken = useMemo(() => {
    if (!spec.ok) return false;
    let isBroken = false;
    walkValues(spec.fields, 1, (node) => {
      if (isBroken || !VALUE_TYPES.has(text(node.type)) || node.required !== true) return;
      // A reserved/duplicate key degrades the field to read-only, so a
      // required field with one can never be filled — same trap as any other
      // unsatisfiable config. keyPlan covers exactly the visible walk order.
      const state = keyPlan.get(node);
      isBroken =
        state?.reserved === true ||
        state?.duplicate === true ||
        requiredIsUnsatisfiable(node);
    });
    return isBroken;
  }, [spec, keyPlan]);

  const setValue = (key: string, value: unknown) => {
    if (!key) return;
    setValues((prev) => ({ ...prev, [key]: value }));
    setErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const errorCard = (head: string, detail: string) => (
    <div className="a2ui-card a2ui-error">
      <div className="a2ui-error-head">{head}</div>
      {detail ? <pre className="a2ui-error-msg">{detail.slice(0, 500)}</pre> : null}
    </div>
  );

  if (!spec.ok) {
    if (spec.reason === "tooMany") return errorCard(t("a2uiTooMany"), spec.detail);
    if (spec.reason === "tooLarge") return errorCard(t("a2uiTooLarge"), spec.detail);
    return errorCard(t("a2uiInvalid"), spec.detail);
  }

  const { title, submitLabel, fields, unknown } = spec;

  const valueOf = (node: RawNode): unknown => {
    const key = text(node.key);
    if (!key) return undefined;
    const type = text(node.type);
    const raw = values[key];
    switch (type) {
      case "TEXT_INPUT": {
        const input = typeof raw === "string" ? raw : "";
        return input.trim() ? input : undefined;
      }
      case "NUMBER_SLIDER": {
        const range = sliderRange(node);
        if (!range.ok) return undefined;
        return sliderValue(range, typeof raw === "number" ? raw : range.def);
      }
      case "CHOICE": {
        if (node.multiple === true) {
          const list = Array.isArray(raw) ? raw.filter((item): item is string => typeof item === "string") : [];
          return list.length > 0 ? list : undefined;
        }
        return typeof raw === "string" && raw ? raw : undefined;
      }
      case "DATE_PICK":
        return typeof raw === "string" && raw ? raw : undefined;
      case "SWITCH":
        return typeof raw === "boolean" ? raw : node.default === true;
      case "BUTTON_GROUP":
        return typeof raw === "string" && raw ? raw : undefined;
      default:
        return undefined;
    }
  };

  const validate = (node: RawNode, value: unknown): string | undefined => {
    if (node.required === true && value === undefined) {
      return t("a2uiRequired");
    }
    if (typeof value !== "string" || !value) return undefined;
    const rule = text(node.validate);
    if (rule === "email" && !EMAIL_RE.test(value)) return t("a2uiInvalidEmail");
    if (rule === "phone" && !PHONE_RE.test(value)) return t("a2uiInvalidPhone");
    if (rule === "regex") {
      const pattern = text(node.pattern);
      if (pattern) {
        const result = checkPattern(pattern, value);
        if (result !== "ok") return t("a2uiInvalidPattern");
      }
    }
    return undefined;
  };

  const submit = () => {
    if (broken) return;
    const payload: Record<string, unknown> = Object.create(null);
    const nextErrors: Record<string, string> = {};
    walkValues(fields, 1, (node) => {
      if (!VALUE_TYPES.has(text(node.type))) return;
      const key = text(node.key);
      const state = keyPlan.get(node);
      // Reserved keys never enter the payload; duplicate instances are
      // degraded at render time and only the first instance is collected.
      if (state?.reserved || state?.duplicate) return;
      const value = valueOf(node);
      if (node.required === true && value === undefined) {
        // Config-level unsatisfiability is already caught by `broken` at
        // render time; reaching here means the user simply left it empty.
        if (requiredIsUnsatisfiable(node)) return;
        nextErrors[key] = t("a2uiRequired");
        return;
      }
      const message = validate(node, value);
      if (message) nextErrors[key] = message;
      if (value !== undefined) payload[key] = value;
    });
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      return;
    }
    const detail = JSON.stringify({ a2ui_submit: payload, a2ui_title: title });
    setSubmitted(detail);
    window.dispatchEvent(new CustomEvent("andagent:composer-fill", { detail }));
  };

  if (submitted !== null) {
    return (
      <div className="a2ui-card a2ui-submitted">
        <div className="a2ui-head">
          {title ? <div className="a2ui-title">{title}</div> : null}
          <span className="a2ui-badge">{t("a2uiSubmitted")}</span>
        </div>
        <pre className="a2ui-summary">{submitted}</pre>
      </div>
    );
  }

  const renderField = (node: RawNode, depth: number, path: string): ReactNode => {
    if (depth > MAX_DEPTH) {
      return (
        <div key={path} className="a2ui-label a2ui-degraded">
          {clip(text(node.text) || text(node.label) || t("a2uiTooDeep"))}
        </div>
      );
    }
    const type = text(node.type);
    const key = text(node.key);
    const group = `${reactId}-${path}`;
    const keyState = keyPlan.get(node);
    if (keyState?.reserved || keyState?.duplicate) {
      // Degraded instead of interactive: reserved keys are never submitted and
      // only the first instance of a duplicated key is collected.
      return (
        <div className="a2ui-field a2ui-degraded" key={path}>
          <div className="a2ui-label">{clip(text(node.label) || key)}</div>
          <div className="a2ui-field-error">
            {keyState.reserved ? t("a2uiReservedKey") : t("a2uiDuplicateKey")}
          </div>
        </div>
      );
    }

    if (VALUE_TYPES.has(type) && !key) {
      // A value field without a key can never be bound or submitted; render it
      // read-only instead of a control that silently ignores every edit.
      return (
        <div className="a2ui-field a2ui-degraded" key={path}>
          <div className="a2ui-label">{clip(text(node.label) || text(node.text) || type)}</div>
        </div>
      );
    }

    switch (type) {
      case "TEXT_INPUT": {
        const label = clip(text(node.label) || key);
        const required = node.required === true;
        const maxLength = Math.max(0, Math.min(10000, Math.floor(finiteNumber(node.max_length, 0))));
        const inputId = `${group}-input`;
        return (
          <div className="a2ui-field" key={path}>
            <label className="a2ui-label" htmlFor={inputId}>
              {label}
              {required ? <span className="a2ui-req"> *</span> : null}
            </label>
            <input
              id={inputId}
              className={`a2ui-input${errors[key] ? " a2ui-input-invalid" : ""}`}
              type="text"
              value={typeof values[key] === "string" ? (values[key] as string) : ""}
              placeholder={clip(text(node.placeholder))}
              maxLength={maxLength > 0 ? maxLength : undefined}
              onChange={(event) => setValue(key, event.target.value.slice(0, maxLength > 0 ? maxLength : undefined))}
            />
            {errors[key] ? <div className="a2ui-field-error">{errors[key]}</div> : null}
          </div>
        );
      }

      case "NUMBER_SLIDER": {
        const label = clip(text(node.label) || key);
        const range = sliderRange(node);
        if (!range.ok) {
          return (
            <div className="a2ui-field a2ui-degraded" key={path}>
              <div className="a2ui-label">{label}</div>
              <div className="a2ui-field-error">{t("a2uiSliderRange")}</div>
            </div>
          );
        }
        const current = sliderValue(
          range,
          typeof values[key] === "number" ? (values[key] as number) : range.def,
        );
        return (
          <div className="a2ui-field" key={path}>
            <div className="a2ui-slider-head">
              <span className="a2ui-label">{label}</span>
              <span className="a2ui-slider-value">{current}</span>
            </div>
            <input
              className="a2ui-slider"
              type="range"
              min={range.min}
              max={range.max}
              step={range.step}
              value={current}
              aria-label={label}
              onChange={(event) => setValue(key, sliderValue(range, Number(event.target.value)))}
            />
          </div>
        );
      }

      case "CHOICE": {
        const label = clip(text(node.label) || key);
        const options = choiceOptions(node.options);
        if (options.length === 0 && node.required === true) {
          return (
            <div className="a2ui-field a2ui-degraded" key={path}>
              <div className="a2ui-label">{label}</div>
              <div className="a2ui-field-error">{t("a2uiEmptyOptions")}</div>
            </div>
          );
        }
        const multiple = node.multiple === true;
        const selected = multiple
          ? Array.isArray(values[key])
            ? (values[key] as string[])
            : []
          : typeof values[key] === "string"
            ? [values[key] as string]
            : [];
        return (
          <div className="a2ui-field" key={path}>
            <div className="a2ui-label">{label}</div>
            <div className="a2ui-choices">
              {options.map((option, index) => {
                const checked = selected.includes(option.value);
                return (
                  <label className="a2ui-choice" key={`${path}-${index}`}>
                    <input
                      type={multiple ? "checkbox" : "radio"}
                      name={group}
                      checked={checked}
                      onChange={(event) => {
                        if (multiple) {
                          const next = event.target.checked
                            ? [...selected, option.value]
                            : selected.filter((item) => item !== option.value);
                          setValue(key, next);
                        } else {
                          setValue(key, option.value);
                        }
                      }}
                    />
                    <span>{option.label}</span>
                  </label>
                );
              })}
            </div>
            {errors[key] ? <div className="a2ui-field-error">{errors[key]}</div> : null}
          </div>
        );
      }

      case "DATE_PICK": {
        const label = clip(text(node.label) || key);
        const inputId = `${group}-date`;
        return (
          <div className="a2ui-field" key={path}>
            <label className="a2ui-label" htmlFor={inputId}>{label}</label>
            <input
              id={inputId}
              className={`a2ui-input${errors[key] ? " a2ui-input-invalid" : ""}`}
              type="date"
              value={typeof values[key] === "string" ? (values[key] as string) : ""}
              onChange={(event) => setValue(key, event.target.value)}
            />
            {errors[key] ? <div className="a2ui-field-error">{errors[key]}</div> : null}
          </div>
        );
      }

      case "SWITCH": {
        const label = clip(text(node.label) || key);
        const checked = typeof values[key] === "boolean" ? (values[key] as boolean) : node.default === true;
        return (
          <div className="a2ui-field" key={path}>
            <label className="a2ui-switch">
              <input
                type="checkbox"
                checked={checked}
                onChange={(event) => setValue(key, event.target.checked)}
              />
              <span>{label}</span>
            </label>
          </div>
        );
      }

      case "BUTTON_GROUP": {
        const label = clip(text(node.label) || "");
        const buttons = buttonOptions(node.buttons);
        if (buttons.length === 0 && node.required === true) {
          return (
            <div className="a2ui-field a2ui-degraded" key={path}>
              <div className="a2ui-label">{label || key}</div>
              <div className="a2ui-field-error">{t("a2uiEmptyOptions")}</div>
            </div>
          );
        }
        const selected = typeof values[key] === "string" ? (values[key] as string) : "";
        return (
          <div className="a2ui-field" key={path}>
            {label ? <div className="a2ui-label">{label}</div> : null}
            <div className="a2ui-button-group">
              {buttons.map((button, index) => (
                <button
                  type="button"
                  key={`${path}-${index}`}
                  className={`a2ui-option-btn${selected === button.value ? " a2ui-option-btn-active" : ""}`}
                  onClick={() => setValue(key, button.value)}
                >
                  {button.label}
                </button>
              ))}
            </div>
          </div>
        );
      }

      case "CARD": {
        return (
          <div className="a2ui-inner-card" key={path}>
            {text(node.title) ? <div className="a2ui-inner-title">{clip(text(node.title))}</div> : null}
            {text(node.text) ? <div className="a2ui-inner-text">{clip(text(node.text))}</div> : null}
          </div>
        );
      }

      case "ROW":
      case "COLUMN": {
        const children = Array.isArray(node.children) ? node.children.filter(isRecord) : [];
        return (
          <div className={type === "ROW" ? "a2ui-row" : "a2ui-col"} key={path}>
            {children.map((child, index) => renderField(child, depth + 1, `${path}-${index}`))}
          </div>
        );
      }

      case "LABEL":
        return (
          <div className="a2ui-label" key={path}>
            {clip(text(node.text))}
          </div>
        );

      default:
        // Unknown catalog entry: read-only label, warning shown once at the bottom.
        return (
          <div className="a2ui-label a2ui-degraded" key={path}>
            {clip(text(node.text) || text(node.label) || type)}
          </div>
        );
    }
  };

  return (
    <div className={collapsed ? "a2ui-card collapsed" : "a2ui-card"}>
      {title ? (
        <button
          type="button"
          className="a2ui-head"
          onClick={() => setCollapsed((v) => !v)}
          aria-expanded={!collapsed}
        >
          <span className="a2ui-title">{title}</span>
          <span className="a2ui-chevron" aria-hidden="true">{collapsed ? "▸" : "▾"}</span>
        </button>
      ) : null}
      {!collapsed && (
        <>
          <div className="a2ui-body">
            {fields.map((field, index) => renderField(field, 1, `${index}`))}
          </div>
          {unknown ? <div className="a2ui-warning">{t("a2uiUnknownType")}</div> : null}
          {broken ? (
            <div className="a2ui-warning a2ui-broken">{t("a2uiBrokenCard")}</div>
          ) : null}
          <div className="a2ui-actions">
            <button
              type="button"
              className="a2ui-submit"
              disabled={broken}
              onClick={submit}
            >
              {submitLabel || t("a2uiSubmit")}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

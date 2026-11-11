import { translate } from "./i18n";
/**
 * Multi-session storage for the drawer: sessions are named PiMessage[]
 * transcripts. v1 legacy key ("andagent.messages.v1") is migrated into the
 * index as the first session on first read.
 *
 * R53: when the native KV bridge is available (Android shell), sessions live
 * in the app-private KV store (`namespace=sessions`, keys `index` / `s_<id>`)
 * so clearing WebView DOM storage no longer loses conversations. Browsers
 * (preview / vitest) keep the original localStorage behavior. The one-time
 * migration copies from localStorage and deliberately leaves the source keys
 * in place (backup, cleaned up in a later pass).
 */

import { hasKvBridge, kvCall } from "./bridge";
import { sumMessageUsage } from "./usage";

export interface SessionMeta {
  id: string;
  title: string;
  updatedAt: number;
  /** R40: 会话 token 累计（input/output），保存时从助手消息 usage 求和。 */
  usage?: { input: number; output: number };
}

const INDEX_KEY = "andagent.sessions.index.v1";
const SESSION_KEY = (id: string) => `andagent.session.${id}`;
const LEGACY_KEY = "andagent.messages.v1";

/** R53: 原生 KV 命名空间与键名。 */
const KV_NS = "sessions";
const KV_INDEX_KEY = "index";
const kvSessionKey = (id: string) => (id.startsWith("s_") ? id : `s_${id}`);

// Weakly typed on purpose: values cross the localStorage serialization boundary.
type PiMessage = any; // eslint-disable-line @typescript-eslint/no-explicit-any

interface KvGetResult {
  found?: boolean;
  value?: unknown;
}

function safeParse<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function sortIndex(list: SessionMeta[]): SessionMeta[] {
  return list.sort((a, b) => b.updatedAt - a.updatedAt);
}

/* ------------------------------- KV layer ------------------------------- */

/** `undefined` = 通道不可用/读失败，`null` = 确认不存在。 */
function kvRead(key: string): string | null | undefined {
  const res = kvCall<KvGetResult>("kv_get", { namespace: KV_NS, key });
  if (res === undefined) return undefined;
  if (!res.found) return null;
  return typeof res.value === "string" ? res.value : null;
}

function kvWrite(key: string, payload: string): boolean {
  const res = kvCall<{ ok?: boolean }>("kv_set", {
    namespace: KV_NS,
    key,
    value_json: JSON.stringify(payload),
  });
  return res?.ok === true;
}

function writeLocal(entries: Pair[]): void {
  for (const [, localKey, value] of entries) {
    try {
      localStorage.setItem(localKey, value);
    } catch (e) {
      console.warn("session persist failed", e);
    }
  }
}

/** [KV 键, localStorage 键, 序列化后的值] */
type Pair = [string, string, string];

/**
 * 写入一组键值。KV 可用时写 KV；任一键失败（例如单值 64KB 上限）则把整组
 * 镜像进 localStorage，保证本次会话内容仍可读回，不会因超限丢消息。
 */
function persist(entries: Pair[]): void {
  if (!hasKvBridge()) {
    writeLocal(entries);
    return;
  }
  let ok = true;
  for (const [key, , value] of entries) {
    if (!kvWrite(key, value)) ok = false;
  }
  if (ok) return;
  console.warn("session KV persist failed, mirroring to localStorage");
  writeLocal(entries);
}

function forget(key: string, localKey: string): void {
  if (hasKvBridge()) kvCall("kv_delete", { namespace: KV_NS, key });
  localStorage.removeItem(localKey);
}

/* ----------------------------- index loading ---------------------------- */

function loadLocalIndex(): SessionMeta[] {
  const list = safeParse<SessionMeta[]>(localStorage.getItem(INDEX_KEY));
  if (list && Array.isArray(list) && list.length > 0) {
    return sortIndex(list);
  }
  // One-time migration: adopt the v1 single-conversation history.
  const legacy = safeParse<PiMessage[]>(localStorage.getItem(LEGACY_KEY));
  if (legacy && Array.isArray(legacy) && legacy.length > 0) {
    // R144 review 必修项①：迁移写入也是落盘入口——旧版单会话历史可能带明文凭据，
    // 写入 SESSION_KEY / 索引标题前统一过 scrub 链（与 migrateToKv 同口径）。
    const counter: ScrubCounter = { replaced: 0 };
    const scrubbedLegacy = scrubValue(legacy, counter) as PiMessage[];
    const firstUser = scrubbedLegacy.find((m) => m.role === "user");
    const title = scrubSecretsInText(firstText(firstUser).slice(0, 24) || translate("pastChat")).text;
    const meta: SessionMeta = { id: newId(), title, updatedAt: Date.now() };
    localStorage.setItem(SESSION_KEY(meta.id), JSON.stringify(scrubbedLegacy));
    localStorage.setItem(INDEX_KEY, JSON.stringify([meta]));
    return [meta];
  }
  return [];
}

/**
 * 首次在 KV 模式下读取：把 localStorage 的会话复制到 KV（复制，不移动）。
 * 单个会话超限时跳过（读取时会回退 localStorage），索引仍写入以便列表完整。
 */
function migrateToKv(): SessionMeta[] {
  const local = loadLocalIndex();
  if (local.length === 0) return [];
  let copied = 0;
  // R144 review 六轮必修项③ + R144 修3：迁移是明文落盘入口——localStorage 旧数据
  // 可能还带着明文凭据，复制进 KV 前必须过同一条脱敏链；解析失败的非 JSON 旧记录
  // 绝不把原文写进 KV（明文副本），跳过迁移、console.warn 记 id 并从迁移索引剔除。
  const migratable: SessionMeta[] = [];
  for (const meta of local) {
    const raw = localStorage.getItem(SESSION_KEY(meta.id));
    if (!raw) {
      // 无本地会话体：保留索引条目（读取会回退 localStorage），不写 KV。
      migratable.push(meta);
      continue;
    }
    const parsed = safeParse<PiMessage[]>(raw);
    if (!parsed) {
      console.warn(`session KV migrate skipped (unparseable): ${meta.id}`);
      continue;
    }
    // 成功解析的记录照旧 scrub 后写入 KV。
    const counter: ScrubCounter = { replaced: 0 };
    const payload = JSON.stringify(scrubValue(parsed, counter));
    if (kvWrite(kvSessionKey(meta.id), payload)) copied++;
    else console.warn(`session KV migrate skipped: ${meta.id}`);
    // 写入失败（如 >64KB）仍保留索引条目，读取会回退 localStorage。
    migratable.push(meta);
  }
  // R144 修3：索引标题脱敏无条件——写入 KV_INDEX_KEY 前对全部 meta.title 过
  // scrubSecretsInText，不再只在成功复制会话体后才做（copied===0 也返回脱敏索引）。
  const scrubbedIndex = sortIndex(
    migratable.map((meta) => ({ ...meta, title: scrubSecretsInText(meta.title).text })),
  );
  if (copied > 0) kvWrite(KV_INDEX_KEY, JSON.stringify(scrubbedIndex));
  return scrubbedIndex;
}

function loadIndex(): SessionMeta[] {
  if (hasKvBridge()) {
    const raw = kvRead(KV_INDEX_KEY);
    if (typeof raw === "string") {
      const list = safeParse<SessionMeta[]>(raw);
      if (Array.isArray(list)) return sortIndex(list);
    }
    if (raw === null) return migrateToKv();
  }
  return loadLocalIndex();
}

/* ------------------------------ session body ---------------------------- */

function loadSession(id: string): PiMessage[] {
  if (hasKvBridge()) {
    const raw = kvRead(kvSessionKey(id));
    if (typeof raw === "string") return safeParse<PiMessage[]>(raw) ?? [];
  }
  return safeParse<PiMessage[]>(localStorage.getItem(SESSION_KEY(id))) ?? [];
}

function firstText(message: PiMessage | undefined): string {
  if (!message) return "";
  const content = (message as { content?: unknown }).content;
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((p) => (p && typeof p === "object" && (p as { text?: string }).text) || "")
      .filter(Boolean)
      .join(" ");
  }
  return "";
}

function newId(): string {
  return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

/* ------------------------- R91.5: secret scrub ------------------------- */

/**
 * 旧版本可能把 `password=…` / `"password":"…"` 写进对话文本。这里在本地
 * 持久化层做一次性除密：命中密码/口令键值就替换成 [REDACTED]，其余内容不动。
 *
 * 注意：已经发送给云端模型的历史无法撤回；设置页会同时提示用户换密码。
 */
type ScrubRule = { re: RegExp; build: (prefix: string) => string };

/**
 * 写成「前缀 + [REDACTED]」而不是 `$1` 模板：前缀只保留键名与分隔符，
 * 值（含引号包裹）整段丢弃。每类值前都有 `(?!\[REDACTED\])` 负向断言，
 * 保证重复执行是幂等的。
 */
const SECRET_PATTERNS: ScrubRule[] = [
  {
    // R144 review 建议项：左侧 \b 词边界——不以 password/passwd/pwd/passphrase 结尾的
    // 普通字段（如 showPasswordHint=true、masterpwd: 1）不被误遮盖。
    re: /(\b(?:["']?(?:password|passwd|pwd|passphrase|key_passphrase)\b["']?)\s*[:=]\s*)(?:"(?!\[REDACTED\])[^"\n]*"|'(?!\[REDACTED\])[^'\n]*'|(?!\[REDACTED\])[^\s"'`,;，。、）)]+)/gi,
    build: (prefix) => `${prefix}[REDACTED]`,
  },
  // R144 review 三轮/四轮必修项①②：URL userinfo 凭据（如 mqtt/ssh 的
  // broker/URL 参数写成 tcp://user:pass@host / https://user:pass@host）。
  // 保留 scheme://，userinfo 整段 → <masked>@（与 Kotlin MqttCodec.sanitizeBroker
  // 按「最后一个 @」遮盖同一约定）；负向断言 (?!<masked>@) 保证幂等。
  // R144 review 六轮必修项②：userinfo 段不允许越过 RFC 3986 的 authority 边界——
  // 各段字符类排除 `?` 与 `#`，query/fragment 里的 `@`（如
  // https://host?email=alice@example.org、page#@section）不再被当成 userinfo
  // 整段吞掉（此前仅排除 `/` 时，无 path 的 query 直连域名形式会被误伤）。
  // 保留 scheme://，userinfo 整段 → <masked>@（与 Kotlin MqttCodec.sanitizeBroker
  // 按「最后一个 @」遮盖同一约定）；负向断言 (?!<masked>@) 保证幂等。
  // userinfo 各段不含 /、空白与引号——普通 URL 路径里的 @（https://host/docs/@alice）
  // 不误伤；无冒号（tcp://alice@host）与多 @（tcp://a:p@ss@host）整段遮盖到最后一个 @。
  {
    re: /(\b\w+:\/\/)(?!<masked>@)[^\s"'@/?#]+(?::[^\s"'@/?#]*)?(?:@[^\s"'@/?#]*)*@/g,
    build: (prefix) => `${prefix}<masked>@`,
  },
];

/** 结构化键名（工具参数对象）：命中即整值替换，不解析值内容。R144 六轮必修项①：username 与 password 是成对凭据，值一并替换。 */
const SECRET_KEYS = /^(?:password|passwd|pwd|passphrase|key_passphrase|secret|username)$/i;

/** 纯函数：返回脱敏后的文本与替换处数。 */
export function scrubSecretsInText(input: string): { text: string; replaced: number } {
  let replaced = 0;
  let text = input;
  for (const { re, build } of SECRET_PATTERNS) {
    text = text.replace(re, (_match: string, prefix: string) => {
      replaced += 1;
      return build(prefix);
    });
  }
  return { text, replaced };
}

interface ScrubCounter { replaced: number }

/** 递归脱敏：字符串走正则，对象里 secret 键名直接整值替换。 */
function scrubValue(value: unknown, counter: ScrubCounter): unknown {
  if (typeof value === "string") {
    const scrubbed = scrubSecretsInText(value);
    counter.replaced += scrubbed.replaced;
    return scrubbed.text;
  }
  if (Array.isArray(value)) return value.map((item) => scrubValue(item, counter));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      if (SECRET_KEYS.test(key) && typeof nested === "string" && nested !== "[REDACTED]") {
        out[key] = "[REDACTED]";
        counter.replaced += 1;
      } else {
        out[key] = scrubValue(nested, counter);
      }
    }
    return out;
  }
  return value;
}

/**
 * 扫描全部会话并就地除密。解析后按消息树脱敏再写回，避免正则跨越 JSON
 * 转义序列导致漏网；解析失败则跳过该会话（宁可不改也不写坏历史）。
 */
function scrubAll(): { sessions: number; replaced: number } {
  let sessions = 0;
  let replaced = 0;
  // R144 review 二轮必修项：索引标题也要扫——旧版本可能在标题里留过明文。
  let indexDirty = false;
  const index = loadIndex().map((meta) => {
    const scrubbedTitle = scrubSecretsInText(meta.title);
    if (scrubbedTitle.replaced > 0) {
      indexDirty = true;
      replaced += scrubbedTitle.replaced;
      return { ...meta, title: scrubbedTitle.text };
    }
    return meta;
  });
  if (indexDirty) {
    persist([[KV_INDEX_KEY, INDEX_KEY, JSON.stringify(sortIndex(index))]]);
  }
  for (const meta of loadIndex()) {
    const messages = loadSession(meta.id);
    if (!messages.length) continue;
    const counter: ScrubCounter = { replaced: 0 };
    const cleaned = scrubValue(messages, counter);
    if (counter.replaced === 0) continue;
    persist([[kvSessionKey(meta.id), SESSION_KEY(meta.id), JSON.stringify(cleaned)]]);
    sessions += 1;
    replaced += counter.replaced;
  }
  return { sessions, replaced };
}

export const SessionStore = {
  list(): SessionMeta[] {
    return loadIndex();
  },

  load(id: string): PiMessage[] {
    return loadSession(id);
  },

  save(id: string, messages: PiMessage[]): SessionMeta[] {
    const index = loadIndex();
    // 空会话不落盘：newSession() 后未发过任何消息时，save 只 flush 已有内容，
    // 不把「新对话」占位写进索引——否则每点一次新会话抽屉就多一条空记录。
    if (messages.length === 0 && !index.some((s) => s.id === id)) {
      return sortIndex(index);
    }
    // R144 review 二轮必修项：标题先脱敏再截断——首条用户消息可能含明文凭据
    // （如「连一下 password=abc123」），明文不得落进 andagent.sessions.index.v1。
    const firstUser = messages.find((m) => m.role === "user");
    const scrubbedList = scrubValue([firstUser], { replaced: 0 }) as PiMessage[];
    const autoTitle = firstText(scrubbedList[0]).slice(0, 24) || translate("newChat");
    const existing = index.find((s) => s.id === id);
    // R144 review 三轮必修项①：沿用旧标题前也除密——旧版本落盘的标题可能
    // 带明文凭据，不能因「标题已存在」绕过 save 的落盘脱敏路径。
    const existingTitle =
      existing?.title && existing.title !== "新对话"
        ? scrubSecretsInText(existing.title).text
        : existing?.title;
    const total = sumMessageUsage(messages);
    const meta: SessionMeta = {
      id,
      title: existingTitle || autoTitle,
      updatedAt: Date.now(),
      usage: { input: total.input, output: total.output },
    };
    const next = [meta, ...index.filter((s) => s.id !== id)];
    // R144 review 必修项①：持久化前统一除密——工具调用参数（如 mqtt_pub/mqtt_sub 的
    // password、ssh_exec 的 key_passphrase）里命中密码/口令键名的值一律替换为
    // [REDACTED] 后再落盘（KvStore 与 localStorage 回退共用此路径）。聊天模型已在
    // 生成参数时被指引「凭据随调用传入」，落盘的是工具调用记录，不再保留明文。
    const scrubbed = scrubValue(messages, { replaced: 0 }) as PiMessage[];
    persist([
      [kvSessionKey(id), SESSION_KEY(id), JSON.stringify(scrubbed)],
      [KV_INDEX_KEY, INDEX_KEY, JSON.stringify(next)],
    ]);
    // KV 模式保留 localStorage 原始键（迁移备份），浏览器模式维持旧行为。
    if (!hasKvBridge()) localStorage.removeItem(LEGACY_KEY);
    return sortIndex(next);
  },

  rename(id: string, title: string): SessionMeta[] {
    const index = loadIndex();
    // R144 review 三轮必修项③：rename 是标题直接落盘入口，与 save 沿用旧标题
    // 同等对待——落盘前除密，防含 userinfo/密码的标题绕过 scrub 链。
    const safeTitle = scrubSecretsInText(title.trim()).text;
    const next = index.map((s) => (s.id === id ? { ...s, title: safeTitle || s.title } : s));
    persist([[KV_INDEX_KEY, INDEX_KEY, JSON.stringify(next)]]);
    return sortIndex(next);
  },

  remove(id: string): SessionMeta[] {
    return this.removeMany([id]);
  },

  removeMany(ids: string[]): SessionMeta[] {
    const removing = new Set(ids);
    if (removing.size === 0) return loadIndex();
    const index = loadIndex().filter((s) => !removing.has(s.id));
    for (const id of removing) forget(kvSessionKey(id), SESSION_KEY(id));
    persist([[KV_INDEX_KEY, INDEX_KEY, JSON.stringify(index)]]);
    return index;
  },

  /** R91.5: 对全部会话做一次明文密码除密（设置页按钮）。 */
  scrubSecrets(): { sessions: number; replaced: number } {
    return scrubAll();
  },
};

import { translate } from "./i18n";
import {
  chatImageBase64,
  chatImageImport,
  type ChatImageImportResult,
  type RawChatImage,
} from "./bridge";

/**
 * R88: 对话内图片附件。
 *
 * 消息里只存「原生落盘后的相对路径」（`chat-images/x.jpg`）：
 *  - 渲染：`/local/<path>` 由 LocalMediaHandler 的只读路由提供，不进 DOM 的 base64；
 *  - 会话持久化：单值 64KB 上限（KV），路径几十字节，base64 绝不能进消息 JSON；
 *  - 模型：发请求前由 [hydrateChatImages] 按路径读回 base64（带进程内缓存）。
 */
export type ChatImage = RawChatImage;

/** 单条消息最多挂几张图（视觉模型 token 成本按张数线性上涨）。 */
export const MAX_CHAT_IMAGES = 4;

/** base64 缓存上限：一张 1568px JPEG ≈ 200-400KB。 */
const CACHE_LIMIT = 8;
const base64Cache = new Map<string, string>();

/** 附件渲染 URL：`/local/` 只读路由（浏览器预览下路径不存在，仅影响开发态）。 */
export function chatImageUrl(path: string): string {
  if (/^(data:|blob:|https?:|\/)/i.test(path)) return path;
  return `/local/${path}`;
}

/** 模型消息用的 base64（无 `data:` 前缀）；失败返回空串。 */
export function chatImageData(path: string): string {
  const cached = base64Cache.get(path);
  if (cached !== undefined) return cached;
  const data = chatImageBase64(path);
  if (!data) return "";
  if (base64Cache.size >= CACHE_LIMIT) {
    const oldest = base64Cache.keys().next().value;
    if (oldest !== undefined) base64Cache.delete(oldest);
  }
  base64Cache.set(path, data);
  return data;
}

/** 从相册/相机/系统分享拿到的 source（content:// 或应用落盘路径）导入为附件。 */
export function importChatImage(source: string): ChatImageImportResult {
  return chatImageImport(source);
}

type ImagePart = { type: "image"; path: string; mimeType?: string; data?: string };
type ModelContentPart = { type?: string; text?: string; path?: string; data?: string; mimeType?: string };

/**
 * 把消息里的 `{type:"image", path}` 补成 pi-ai 的 ImageContent
 * `{type:"image", data, mimeType}`。只影响交给模型的副本，消息本体不动。
 * 读不到图时降级成一句说明，避免把空图发给模型。
 */
export function hydrateChatImages<T extends { content: unknown }>(messages: T[]): T[] {
  let changed = false;
  const out = messages.map((message) => {
    const content = message.content;
    if (!Array.isArray(content)) return message;
    let touched = false;
    const parts = content.map((raw: unknown) => {
      if (!raw || typeof raw !== "object") return raw;
      const part = raw as ModelContentPart;
      if (part.type !== "image" || part.data || !part.path) return raw;
      touched = true;
      const data = chatImageData(part.path);
      if (!data) return { type: "text", text: translate("imageMissing") };
      return { type: "image", data, mimeType: part.mimeType || "image/jpeg" };
    });
    if (!touched) return message;
    changed = true;
    return { ...message, content: parts } as T;
  });
  return changed ? out : messages;
}

/** 消息里的图片附件（重试/重发时沿用原图）。 */
export function imagesOfMessage(message: { content: unknown }): ChatImage[] {
  const content = message.content;
  if (!Array.isArray(content)) return [];
  return content
    .filter((raw): raw is ImagePart => !!raw && typeof raw === "object" && (raw as ImagePart).type === "image")
    .filter((part) => !!part.path)
    .map((part) => ({
      path: part.path,
      url: chatImageUrl(part.path),
      width: 0,
      height: 0,
      bytes: 0,
      mimeType: part.mimeType || "image/jpeg",
    }));
}

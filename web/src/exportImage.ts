import { execTool } from "./bridge";
import { currentLanguage, translate } from "./i18n";
import { SessionStore } from "./sessions";
import { resolvedTheme } from "./theme";

/**
 * R95: 对话导出长图（小红书/朋友圈分享形态）。
 *
 * 零依赖自绘 Canvas：会话直接取自 SessionStore，按固定 720px 宽排版后一次性
 * 绘制到离屏画布，再走 image_save（MediaStore）落相册，可选直接拉起分享。
 *
 * 不走 html2canvas：不克隆 DOM，所以 ECharts / mermaid / html 等围栏卡片天然
 * 降级成占位框（标注「图表内容见原会话」），WebView 里也不会踩 oklch 等新色彩
 * 函数的解析坑；文字换行、气泡配色全部自己控制，主题色与 App 一致。
 */

/** 出图宽度（CSS px）。 */
export const LONG_IMAGE_WIDTH = 720;
/** 高度上限：单张更长的图在相册/分享里都难用，超过就提示改用 Markdown。 */
export const LONG_IMAGE_MAX_HEIGHT = 15_000;

/* ------------------------------------------------------------------ */
/* 数据模型                                                            */
/* ------------------------------------------------------------------ */

type StoredPart = {
  type?: string;
  text?: string;
  thinking?: string;
  name?: string;
  id?: string;
};
type StoredMessage = {
  role?: string;
  timestamp?: number;
  content?: string | StoredPart[];
  toolCallId?: string;
};

export type LongImageBlock =
  | { kind: "text"; text: string }
  | { kind: "code"; text: string; lang: string }
  | { kind: "thinking"; text: string }
  | { kind: "tool"; name: string; summary: string }
  | { kind: "placeholder"; label: string };

export interface LongImageSection {
  role: "user" | "assistant";
  at: number;
  blocks: LongImageBlock[];
}

/** 渲染成占位框的围栏语言：这些卡片要 canvas 才能真正绘制，长图里降级。 */
const RICH_FENCES = new Set([
  "chart", "echarts", "mermaid", "live", "canvas", "html", "svg",
  "photos", "image", "widget", "a2ui", "shader", "camera", "terminal", "dbgui",
]);

const CODE_MAX_LINES = 30;
const THINKING_MAX_CHARS = 320;

function messageText(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content.map((part) => (part as StoredPart)?.text ?? "").filter(Boolean).join("\n");
  }
  return "";
}

function oneLine(text: string, limit: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > limit ? `${flat.slice(0, limit)}…` : flat;
}

/** 去掉行内 Markdown 标记，长图只画纯文本（加粗/斜体在分享图里差异很小）。 */
export function stripInlineMarkdown(text: string): string {
  return text
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "［图片］")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/(\*\*|__)(.*?)\1/g, "$2")
    .replace(/~~(.*?)~~/g, "$1")
    .replace(/(^|[\s（(])\*(?!\s)(.*?)(?<!\s)\*/g, "$1$2")
    .replace(/<\/?[a-zA-Z][^>]*>/g, "")
    .trimEnd();
}

/** 一段 Markdown → 长图区块（段落 / 代码 / 占位框）。 */
export function markdownToBlocks(markdown: string): LongImageBlock[] {
  const blocks: LongImageBlock[] = [];
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  let paragraph: string[] = [];
  const flush = () => {
    const text = paragraph.join("\n").trimEnd();
    if (text.trim()) blocks.push({ kind: "text", text: stripInlineMarkdown(text) });
    paragraph = [];
  };

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const fence = /^\s*```+\s*([\w-]*)\s*$/.exec(line);
    if (fence) {
      flush();
      const lang = (fence[1] ?? "").toLowerCase();
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !/^\s*```+\s*$/.test(lines[i])) {
        body.push(lines[i]);
        i += 1;
      }
      if (RICH_FENCES.has(lang)) {
        blocks.push({ kind: "placeholder", label: lang.toUpperCase() });
      } else {
        blocks.push({ kind: "code", lang, text: body.slice(0, CODE_MAX_LINES).join("\n") });
      }
      continue;
    }
    // GFM 表格：分隔行丢弃，其余按行拼成一行，保留可读的列关系。
    if (/^\s*\|?[\s:|-]+\|?\s*$/.test(line) && line.includes("-")) continue;
    if (/^\s*\|/.test(line)) {
      paragraph.push(line.replace(/^\s*\|/, "").replace(/\|\s*$/, "").split("|").map((c) => c.trim()).join("  "));
      continue;
    }
    if (/^\s*<[a-zA-Z/]/.test(line) && !/<[a-zA-Z][^>]*>.*\S/.test(line)) {
      // 独占一行的 HTML 标签（<div>、</p>…）不参与排版。
      flush();
      continue;
    }
    if (!line.trim()) {
      flush();
      continue;
    }
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (heading) {
      flush();
      blocks.push({ kind: "text", text: stripInlineMarkdown(heading[1]) });
      continue;
    }
    paragraph.push(
      line
        .replace(/^\s*>\s?/, "▏")
        .replace(/^\s*[-*+]\s+/, "· ")
        .replace(/^\s*(\d+)[.)]\s+/, "$1. ")
        .replace(/^\s*((-{3,})|(\*{3,})|(_{3,}))\s*$/, ""),
    );
  }
  flush();
  return blocks;
}

/** 会话 → 长图分区（用户/助手气泡 + 思考摘要 + 工具卡片占位）。 */
export function conversationSections(id: string): LongImageSection[] {
  const messages = SessionStore.load(id) as StoredMessage[];
  const results = new Map<string, string>();
  for (const message of messages) {
    if (message.role === "toolResult" && message.toolCallId) {
      results.set(message.toolCallId, messageText(message.content));
    }
  }
  const sections: LongImageSection[] = [];
  for (const message of messages) {
    if (message.role !== "user" && message.role !== "assistant") continue;
    const role = message.role;
    const blocks: LongImageBlock[] = [];
    if (typeof message.content === "string") {
      blocks.push(...markdownToBlocks(message.content));
    } else if (Array.isArray(message.content)) {
      for (const part of message.content) {
        if (part.type === "thinking" && part.thinking) {
          blocks.push({ kind: "thinking", text: oneLine(part.thinking, THINKING_MAX_CHARS) });
        } else if (part.type === "text" && part.text) {
          blocks.push(...markdownToBlocks(part.text));
        } else if (part.type === "toolCall") {
          const summary = oneLine(results.get(part.id ?? "") ?? translate("noResult"), 150);
          blocks.push({ kind: "tool", name: part.name ?? translate("unknown"), summary });
        }
      }
    }
    if (blocks.length > 0) sections.push({ role, at: message.timestamp ?? 0, blocks });
  }
  return sections;
}

/* ------------------------------------------------------------------ */
/* 排版（纯函数，measure 由调用方注入，方便单测）                        */
/* ------------------------------------------------------------------ */

export type Measure = (text: string, font: string) => number;

export type ImageOp =
  | { op: "fill"; x: number; y: number; w: number; h: number; r: number; color: string }
  | { op: "stroke"; x: number; y: number; w: number; h: number; r: number; color: string; width: number; dash?: number[] }
  | { op: "gradient"; x: number; y: number; w: number; h: number; from: string; to: string }
  | { op: "text"; x: number; y: number; text: string; font: string; color: string; align?: "left" | "right" | "center" };

export interface LongImageLayout {
  width: number;
  height: number;
  ops: ImageOp[];
}

export interface LayoutOptions {
  title: string;
  generatedAt: number;
  measure: Measure;
  dark?: boolean;
}

const SANS = `"PingFang SC","Noto Sans SC","Helvetica Neue",Arial,sans-serif`;
const MONO = `"SF Mono",Menlo,Consolas,"Noto Sans Mono",monospace`;
const font = (size: number, weight: number | string = 400, mono = false) =>
  `${weight} ${size}px ${mono ? MONO : SANS}`;

const MARGIN = 28;
const BUBBLE_PAD = 16;
const CONTENT_W = LONG_IMAGE_WIDTH - MARGIN * 2;
const USER_MAX_W = 520;
const HEADER_H = 104;
const BODY_SIZE = 15;
const BODY_LINE = 26;
const BLOCK_GAP = 10;
const MSG_GAP = 18;

interface Palette {
  bg: string;
  text: string;
  muted: string;
  border: string;
  userBg: string;
  userText: string;
  bubbleBg: string;
  bubbleText: string;
  accent: string;
  accent2: string;
  codeBg: string;
  codeText: string;
  cardBg: string;
  cardBorder: string;
  placeholderBg: string;
  placeholderBorder: string;
}

function palette(dark: boolean): Palette {
  return dark
    ? {
        bg: "#0b0d11",
        text: "#e9edf5",
        muted: "#8b93a3",
        border: "rgba(255,255,255,0.12)",
        userBg: "#2b3553",
        userText: "#eef1ff",
        bubbleBg: "#12151b",
        bubbleText: "#e9edf5",
        accent: "#7f9bff",
        accent2: "#a07bff",
        codeBg: "#0b0e14",
        codeText: "#e6d9ff",
        cardBg: "#171b23",
        cardBorder: "rgba(255,255,255,0.10)",
        placeholderBg: "#141821",
        placeholderBorder: "rgba(127,155,255,0.35)",
      }
    : {
        bg: "#f5f7fa",
        text: "#1a1f28",
        muted: "#5a6472",
        border: "rgba(26,31,40,0.10)",
        userBg: "#dfe6fb",
        userText: "#141a24",
        bubbleBg: "#ffffff",
        bubbleText: "#1a1f28",
        accent: "#5b74e6",
        accent2: "#8a5ce6",
        codeBg: "#f1f4fa",
        codeText: "#553a9e",
        cardBg: "#eef1f6",
        cardBorder: "rgba(26,31,40,0.08)",
        placeholderBg: "#f1f4fa",
        placeholderBorder: "rgba(91,116,230,0.35)",
      };
}

/** 逐字符折行：优先在空格处断，中文可任意断（超长英文单词也硬断）。 */
export function wrapText(measure: (text: string) => number, raw: string, maxWidth: number): string[] {
  const out: string[] = [];
  for (const source of raw.split("\n")) {
    if (!source.trim()) {
      out.push("");
      continue;
    }
    let line = "";
    let spaceAt = -1;
    for (const ch of source) {
      const candidate = line + ch;
      if (line && measure(candidate) > maxWidth) {
        let cut = line.length;
        if (spaceAt > 0 && line.length - spaceAt <= 16) cut = spaceAt;
        const head = line.slice(0, cut).trimEnd();
        const rest = line.slice(cut).trimStart();
        if (head) out.push(head);
        line = rest + ch;
        spaceAt = -1;
      } else {
        line = candidate;
        if (ch === " ") spaceAt = line.length;
      }
    }
    out.push(line.trimEnd());
  }
  return out;
}

function truncate(measure: Measure, text: string, style: string, maxWidth: number): string {
  if (measure(text, style) <= maxWidth) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measure(`${text.slice(0, mid)}…`, style) <= maxWidth) lo = mid;
    else hi = mid - 1;
  }
  return `${text.slice(0, lo)}…`;
}

function timeText(at: number): string {
  if (!at) return translate("unknownTime");
  return new Date(at).toLocaleString(currentLanguage() === "zh" ? "zh-CN" : "en-US", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

interface Planned {
  height: number;
  width: number;
  draw: (x: number, y: number) => ImageOp[];
}

function planText(
  block: LongImageBlock & { kind: "text" },
  wrapW: number,
  measure: Measure,
  color: string,
): Planned {
  const style = font(BODY_SIZE);
  const lines = wrapText((text) => measure(text, style), block.text, wrapW);
  let width = 0;
  for (const line of lines) width = Math.max(width, measure(line, style));
  const offset = Math.round((BODY_LINE - BODY_SIZE) / 2);
  return {
    height: Math.max(1, lines.length) * BODY_LINE,
    width,
    draw: (x, y) =>
      lines.map((line, i) => ({
        op: "text",
        x,
        y: y + i * BODY_LINE + offset,
        text: line,
        font: style,
        color,
      })),
  };
}

function planCode(block: LongImageBlock & { kind: "code" }, wrapW: number, measure: Measure, p: Palette): Planned {
  const style = font(12.5, 400, true);
  const lineH = 19;
  const pad = 12;
  const inner = wrapW - pad * 2;
  const lines = block.text.split("\n").slice(0, CODE_MAX_LINES).map((line) => truncate(measure, line, style, inner));
  let width = 0;
  for (const line of lines) width = Math.max(width, measure(line, style));
  return {
    height: lines.length * lineH + pad * 2,
    width,
    draw: (x, y) => [
      { op: "fill", x, y, w: wrapW, h: lines.length * lineH + pad * 2, r: 10, color: p.codeBg },
      ...lines.map((line, i) => ({
        op: "text" as const,
        x: x + pad,
        y: y + pad + i * lineH + 3,
        text: line,
        font: style,
        color: p.codeText,
      })),
    ],
  };
}

function planThinking(block: LongImageBlock & { kind: "thinking" }, wrapW: number, measure: Measure, p: Palette): Planned {
  const label = translate("longImageThinking");
  const labelFont = font(11.5, 600);
  const style = font(13);
  const lineH = 21;
  const pad = 12;
  const bar = 3;
  const inner = wrapW - pad * 2 - bar - 8;
  const wrapped = wrapText((text) => measure(text, style), block.text, inner);
  const maxLines = 6;
  const lines = wrapped.length > maxLines
    ? [...wrapped.slice(0, maxLines - 1), truncate(measure, wrapped.slice(maxLines - 1).join(" "), style, inner)]
    : wrapped;
  const height = pad * 2 + 18 + lines.length * lineH;
  return {
    height,
    width: wrapW,
    draw: (x, y) => [
      { op: "fill", x, y, w: wrapW, h: height, r: 10, color: p.cardBg },
      { op: "fill", x: x + pad, y: y + pad + 2, w: bar, h: height - pad * 2 - 4, r: 2, color: p.accent },
      { op: "text", x: x + pad + bar + 8, y: y + pad, text: label, font: labelFont, color: p.muted },
      ...lines.map((line, i) => ({
        op: "text" as const,
        x: x + pad + bar + 8,
        y: y + pad + 20 + i * lineH,
        text: line,
        font: style,
        color: p.muted,
      })),
    ],
  };
}

function planTool(block: LongImageBlock & { kind: "tool" }, wrapW: number, measure: Measure, p: Palette): Planned {
  const nameFont = font(12.5, 600, true);
  const descFont = font(11.5);
  const pad = 10;
  const dot = 7;
  const inner = wrapW - pad * 2 - dot - 7;
  const header = block.name;
  const detail = truncate(measure, block.summary, descFont, wrapW - pad * 2);
  const height = pad * 2 + 18 + 16;
  return {
    height,
    width: wrapW,
    draw: (x, y) => [
      { op: "fill", x, y, w: wrapW, h: height, r: 10, color: p.cardBg },
      { op: "stroke", x, y, w: wrapW, h: height, r: 10, color: p.cardBorder, width: 1 },
      { op: "fill", x: x + pad, y: y + pad + 4, w: dot, h: dot, r: 2, color: p.accent },
      {
        op: "text",
        x: x + pad + dot + 7,
        y: y + pad,
        text: truncate(measure, header, nameFont, inner),
        font: nameFont,
        color: p.accent,
      },
      { op: "text", x: x + pad, y: y + pad + 20, text: detail, font: descFont, color: p.muted },
    ],
  };
}

function planPlaceholder(block: LongImageBlock & { kind: "placeholder" }, wrapW: number, measure: Measure, p: Palette): Planned {
  const labelFont = font(12.5, 600);
  const noteFont = font(11.5);
  const height = 74;
  const note = translate("longImageRichPlaceholder");
  return {
    height,
    width: wrapW,
    draw: (x, y) => [
      { op: "fill", x, y, w: wrapW, h: height, r: 10, color: p.placeholderBg },
      { op: "stroke", x, y, w: wrapW, h: height, r: 10, color: p.placeholderBorder, width: 1, dash: [6, 4] },
      {
        op: "text",
        x: x + wrapW / 2,
        y: y + 20,
        text: `［${block.label}］`,
        font: labelFont,
        color: p.accent,
        align: "center",
      },
      {
        op: "text",
        x: x + wrapW / 2,
        y: y + 42,
        text: note,
        font: noteFont,
        color: p.muted,
        align: "center",
      },
    ],
  };
}

function planBlock(block: LongImageBlock, wrapW: number, measure: Measure, p: Palette, bodyColor: string): Planned {
  switch (block.kind) {
    case "text":
      return planText(block, wrapW, measure, bodyColor);
    case "code":
      return planCode(block, wrapW, measure, p);
    case "thinking":
      return planThinking(block, wrapW, measure, p);
    case "tool":
      return planTool(block, wrapW, measure, p);
    case "placeholder":
      return planPlaceholder(block, wrapW, measure, p);
  }
}

/** 纯排版：返回画布尺寸与全部绘制指令（不做任何 DOM/Canvas 依赖）。 */
export function layoutLongImage(sections: LongImageSection[], options: LayoutOptions): LongImageLayout {
  const { measure, title, generatedAt } = options;
  const p = palette(options.dark ?? resolvedTheme() === "dark");
  const ops: ImageOp[] = [];

  /* 顶部：会话标题 + 时间水印（渐变底） */
  const titleFont = font(21, 700);
  const subFont = font(12);
  const brandFont = font(13, 700);
  ops.push({ op: "gradient", x: 0, y: 0, w: LONG_IMAGE_WIDTH, h: HEADER_H, from: p.accent, to: p.accent2 });
  ops.push({
    op: "text",
    x: MARGIN,
    y: 28,
    text: truncate(measure, title.replace(/\s+/g, " ").trim() || translate("newChat"), titleFont, CONTENT_W - 110),
    font: titleFont,
    color: "#ffffff",
  });
  ops.push({
    op: "text",
    x: MARGIN,
    y: 62,
    text: `${timeText(generatedAt)} · ${translate("longImageMessageCount", { count: sections.length })}`,
    font: subFont,
    color: "rgba(255,255,255,0.82)",
  });
  ops.push({ op: "text", x: LONG_IMAGE_WIDTH - MARGIN, y: 62, text: "AndAgent", font: brandFont, color: "rgba(255,255,255,0.92)", align: "right" });

  let y = HEADER_H + 26;

  for (const section of sections) {
    const isUser = section.role === "user";
    const roleLabel = `${isUser ? translate("user") : translate("assistant")}${section.at ? ` · ${timeText(section.at)}` : ""}`;
    ops.push({
      op: "text",
      x: isUser ? LONG_IMAGE_WIDTH - MARGIN : MARGIN,
      y,
      text: roleLabel,
      font: font(11.5, 600),
      color: p.muted,
      align: isUser ? "right" : "left",
    });
    y += 20;

    const maxBubbleW = isUser ? USER_MAX_W : CONTENT_W;
    const innerW = maxBubbleW - BUBBLE_PAD * 2;
    const bodyColor = isUser ? p.userText : p.bubbleText;
    const planned = section.blocks.map((block) => planBlock(block, innerW, measure, p, bodyColor));
    const contentW = planned.reduce((max, item) => Math.max(max, item.width), 0);
    // 含卡片/代码块的气泡必须占满宽度：卡片按 innerW 排版，气泡再收缩就会溢出。
    const hasCard = section.blocks.some((block) => block.kind !== "text");
    const bubbleW = !isUser
      ? CONTENT_W
      : hasCard
        ? maxBubbleW
        : Math.min(maxBubbleW, Math.ceil(contentW) + BUBBLE_PAD * 2 + 2);
    const contentH = planned.reduce((sum, item) => sum + item.height, 0) + Math.max(0, planned.length - 1) * BLOCK_GAP;
    const bubbleH = contentH + BUBBLE_PAD * 2;
    const bubbleX = isUser ? LONG_IMAGE_WIDTH - MARGIN - bubbleW : MARGIN;

    ops.push({ op: "fill", x: bubbleX, y, w: bubbleW, h: bubbleH, r: 16, color: isUser ? p.userBg : p.bubbleBg });
    if (!isUser) ops.push({ op: "stroke", x: bubbleX, y, w: bubbleW, h: bubbleH, r: 16, color: p.border, width: 1 });

    let cy = y + BUBBLE_PAD;
    for (const item of planned) {
      ops.push(...item.draw(bubbleX + BUBBLE_PAD, cy));
      cy += item.height + BLOCK_GAP;
    }

    y += bubbleH + MSG_GAP;
  }

  /* 底部水印 */
  const footerFont = font(11);
  ops.push({
    op: "text",
    x: LONG_IMAGE_WIDTH / 2,
    y: y + 6,
    text: `AndAgent · ${translate("longImageGeneratedAt", { time: timeText(generatedAt) })}`,
    font: footerFont,
    color: p.muted,
    align: "center",
  });

  return { width: LONG_IMAGE_WIDTH, height: Math.ceil(y + 34), ops };
}

/* ------------------------------------------------------------------ */
/* Canvas 绘制                                                         */
/* ------------------------------------------------------------------ */

/** 高度越大分辨率越低，避免超长图撑爆 WebView 的位图内存。 */
export function pixelRatio(cssHeight: number): number {
  if (cssHeight <= 4_000) return 2;
  if (cssHeight <= 8_000) return 1.6;
  return 1.2;
}

function roundRectPath(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  const radius = Math.max(0, Math.min(r, Math.min(w, h) / 2));
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + w - radius, y);
  ctx.arcTo(x + w, y, x + w, y + radius, radius);
  ctx.lineTo(x + w, y + h - radius);
  ctx.arcTo(x + w, y + h, x + w - radius, y + h, radius);
  ctx.lineTo(x + radius, y + h);
  ctx.arcTo(x, y + h, x, y + h - radius, radius);
  ctx.lineTo(x, y + radius);
  ctx.arcTo(x, y, x + radius, y, radius);
  ctx.closePath();
}

/** 把排版指令画到 canvas 上。 */
export function paintOps(ctx: CanvasRenderingContext2D, ops: ImageOp[]): void {
  ctx.textBaseline = "top";
  for (const op of ops) {
    if (op.op === "fill") {
      roundRectPath(ctx, op.x, op.y, op.w, op.h, op.r);
      ctx.fillStyle = op.color;
      ctx.fill();
    } else if (op.op === "stroke") {
      roundRectPath(ctx, op.x, op.y, op.w, op.h, op.r);
      ctx.strokeStyle = op.color;
      ctx.lineWidth = op.width;
      ctx.setLineDash(op.dash ?? []);
      ctx.stroke();
      ctx.setLineDash([]);
    } else if (op.op === "gradient") {
      const gradient = ctx.createLinearGradient(op.x, op.y, op.x + op.w, op.y + op.h);
      gradient.addColorStop(0, op.from);
      gradient.addColorStop(1, op.to);
      ctx.fillStyle = gradient;
      ctx.fillRect(op.x, op.y, op.w, op.h);
    } else {
      ctx.font = op.font;
      ctx.fillStyle = op.color;
      ctx.textAlign = op.align ?? "left";
      ctx.fillText(op.text, op.x, op.y);
    }
  }
  ctx.textAlign = "left";
}

function measureWith(ctx: CanvasRenderingContext2D): Measure {
  return (text, style) => {
    ctx.font = style;
    return ctx.measureText(text).width;
  };
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error(translate("readPngFailed")));
    reader.readAsDataURL(blob);
  });
}

/**
 * 渲染会话长图，返回 dataURL；内容超长/环境不支持时返回 "ERROR: …"。
 */
export async function renderConversationImage(sections: LongImageSection[], title: string): Promise<string> {
  const probe = document.createElement("canvas");
  const probeCtx = probe.getContext("2d");
  if (!probeCtx) return `ERROR: ${translate("canvasUnavailable")}`;

  const generatedAt = Date.now();
  const layout = layoutLongImage(sections, { title, generatedAt, measure: measureWith(probeCtx) });
  if (layout.height > LONG_IMAGE_MAX_HEIGHT) {
    return `ERROR: ${translate("longImageTooLong", { height: LONG_IMAGE_MAX_HEIGHT })}`;
  }

  const ratio = pixelRatio(layout.height);
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(layout.width * ratio);
  canvas.height = Math.round(layout.height * ratio);
  const ctx = canvas.getContext("2d");
  if (!ctx) return `ERROR: ${translate("canvasUnavailable")}`;

  ctx.scale(ratio, ratio);
  ctx.fillStyle = palette(resolvedTheme() === "dark").bg;
  ctx.fillRect(0, 0, layout.width, layout.height);
  paintOps(ctx, layout.ops);

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) return `ERROR: ${translate("pngEncodeFailed")}`;
  return blobToDataUrl(blob);
}

/** 文件名用的时间戳：20261108-153012。 */
function stamp(at: number): string {
  const d = new Date(at);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

/**
 * 一键导出会话长图：渲染 → image_save（Pictures/AndAgent）→ 返回原生结果文案。
 * share=true 时原生侧保存后直接拉起系统分享面板。
 */
export async function exportConversationImage(
  id: string,
  title: string,
  options: { share?: boolean } = {},
): Promise<string> {
  try {
    const sections = conversationSections(id);
    if (sections.length === 0) return `ERROR: ${translate("longImageEmpty")}`;
    const dataUrl = await renderConversationImage(sections, title);
    if (dataUrl.startsWith("ERROR:")) return dataUrl;

    const at = Date.now();
    const result = await execTool("image_save", {
      base64: dataUrl,
      filename: `andagent-chat-${stamp(at)}.png`,
      display_name: `${title.replace(/\s+/g, " ").slice(0, 40) || "chat"}.png`,
      share: options.share === true,
    });
    return result.ok ? result.result : `ERROR: ${result.result}`;
  } catch (e) {
    return `ERROR: ${translate("longImageFailed", { message: e instanceof Error ? e.message : String(e) })}`;
  }
}

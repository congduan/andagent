import { currentLanguage, translate } from "./i18n";
import { SessionStore, type SessionMeta } from "./sessions";

type Part = { type?: string; text?: string; thinking?: string; name?: string; id?: string; content?: unknown };
type Message = { role?: string; timestamp?: number; content?: string | Part[]; toolCallId?: string; isError?: boolean };

function textOf(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map((p) => p?.text ?? "").join("\n");
  return "";
}

export function conversationMarkdown(id: string, title: string): string {
  const messages = SessionStore.load(id) as Message[];
  const results = new Map<string, string>();
  for (const message of messages) {
    if (message.role === "toolResult" && message.toolCallId) {
      results.set(message.toolCallId, textOf(message.content));
    }
  }
  const lines = [`# ${title.replace(/\r?\n/g, " ")}`, ""];
  for (const message of messages) {
    if (message.role !== "user" && message.role !== "assistant") continue;
    const time = message.timestamp ? new Date(message.timestamp).toLocaleString(currentLanguage() === "zh" ? "zh-CN" : "en-US") : translate("unknownTime");
    lines.push(`## ${message.role === "user" ? translate("user") : translate("assistant")} · ${time}`, "");
    if (typeof message.content === "string") lines.push(message.content, "");
    else if (Array.isArray(message.content)) {
      for (const part of message.content) {
        if (part.type === "thinking" && part.thinking) {
          lines.push(`> [${translate("reasoningCopy")}]`, ...part.thinking.split("\n").map((line) => `> ${line}`), "");
        } else if (part.type === "text" && part.text) lines.push(part.text, "");
        else if (part.type === "toolCall") {
          const summary = (results.get(part.id ?? "") ?? translate("noResult")).replace(/\s+/g, " ").slice(0, 180);
          lines.push(`> [${translate("toolCopy")}] ${part.name ?? translate("unknown")} → ${summary}`, "");
        }
      }
    }
  }
  return lines.join("\n").trimEnd() + "\n";
}

export function batchConversationMarkdown(sessions: SessionMeta[]): string {
  return sessions.map((session) => conversationMarkdown(session.id, session.title).trimEnd()).join("\n\n---\n\n") + "\n";
}

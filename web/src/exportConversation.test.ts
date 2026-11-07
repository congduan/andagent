import { beforeEach, expect, it } from "vitest";
import { batchConversationMarkdown, conversationMarkdown, hasExportableMessages } from "./exportConversation";
import { SessionStore } from "./sessions";

beforeEach(() => {
  localStorage.clear();
  delete window.AndroidBridge;
});

it("joins selected conversations once, in selection order, with a Markdown separator", () => {
  SessionStore.save("s_a", [{ role: "user", content: "first message" }]);
  SessionStore.save("s_b", [{ role: "user", content: "second message" }]);
  const selected = [
    { id: "s_b", title: "Second", updatedAt: 2 },
    { id: "s_a", title: "First", updatedAt: 1 },
  ];

  expect(batchConversationMarkdown(selected)).toBe(
    `${conversationMarkdown("s_b", "Second").trimEnd()}\n\n---\n\n${conversationMarkdown("s_a", "First")}`,
  );
});

it("treats a conversation without user/assistant messages as not exportable", () => {
  expect(hasExportableMessages("s_missing")).toBe(false);
  SessionStore.save("s_system", [{ role: "system", content: "prompt only" }]);
  expect(hasExportableMessages("s_system")).toBe(false);
  SessionStore.save("s_chat", [{ role: "user", content: "hello" }]);
  expect(hasExportableMessages("s_chat")).toBe(true);
});

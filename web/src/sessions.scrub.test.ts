import { beforeEach, describe, expect, it } from "vitest";
import { SessionStore, scrubSecretsInText } from "./sessions";

beforeEach(() => {
  localStorage.clear();
  delete window.AndroidBridge;
});

describe("scrubSecretsInText", () => {
  it("替换 JSON 形式与自由文本里的密码", () => {
    const jsonLike = 'ssh_connect({"host":"h","password":"hunter2"})';
    const plain = "登录用 password=pa55w0rd 试一下";
    const keyPhrase = "key_passphrase: unlock-me";

    for (const input of [jsonLike, plain, keyPhrase]) {
      const { text, replaced } = scrubSecretsInText(input);
      expect(replaced).toBeGreaterThan(0);
      expect(text).toContain("[REDACTED]");
    }
    expect(scrubSecretsInText(jsonLike).text).not.toContain("hunter2");
    expect(scrubSecretsInText(plain).text).not.toContain("pa55w0rd");
    expect(scrubSecretsInText(keyPhrase).text).not.toContain("unlock-me");
  });

  it("普通说明文字保持原样", () => {
    const text = "密码通过 SSH 凭据管理，不在对话里传递。";
    const result = scrubSecretsInText(text);
    expect(result.replaced).toBe(0);
    expect(result.text).toBe(text);
  });

  it("已脱敏的片段不会二次替换", () => {
    const once = scrubSecretsInText("password=abc123").text;
    const twice = scrubSecretsInText(once);
    expect(twice.replaced).toBe(0);
    expect(twice.text).toBe(once);
  });
});

describe("SessionStore.scrubSecrets", () => {
  it("清理已持久化会话且保留其余内容", () => {
    SessionStore.save("s_scrub", [
      { role: "user", content: "帮我连 ssh password=abc123 谢谢" },
      { role: "assistant", content: "好的" },
    ]);
    const result = SessionStore.scrubSecrets();
    expect(result.replaced).toBeGreaterThan(0);

    const saved = JSON.stringify(SessionStore.load("s_scrub"));
    expect(saved).not.toContain("abc123");
    expect(saved).toContain("[REDACTED]");
    expect(saved).toContain("好的");
  });

  it("结构化参数对象里的 password 键整值替换", () => {
    SessionStore.save("s_obj", [
      { role: "assistant", content: "", tool_calls: [{ function: { name: "ssh_connect", arguments: { password: "topsecret" } } }] },
    ]);
    SessionStore.scrubSecrets();
    const saved = SessionStore.load("s_obj");
    expect(JSON.stringify(saved)).not.toContain("topsecret");
    expect(JSON.stringify(saved)).toContain("[REDACTED]");
  });

  it("没有敏感内容时不改动任何会话", () => {
    SessionStore.save("s_clean", [{ role: "user", content: "看看天气" }]);
    const before = localStorage.getItem("andagent.session.s_clean");
    const result = SessionStore.scrubSecrets();
    expect(result.replaced).toBe(0);
    expect(localStorage.getItem("andagent.session.s_clean")).toBe(before);
  });
});

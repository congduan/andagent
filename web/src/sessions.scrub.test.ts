import { beforeEach, describe, expect, it, vi } from "vitest";
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

  it("R144 review 建议项：以 password 结尾的普通字段不被误遮盖", () => {
    const text = "showPasswordHint=true and masterpwd: 42, but password=hunter2 must go";
    const result = scrubSecretsInText(text);
    expect(result.text).toContain("showPasswordHint=true");
    expect(result.text).toContain("masterpwd: 42");
    expect(result.text).not.toContain("hunter2");
  });
});

describe("SessionStore.scrubSecrets", () => {
  it("遗留明文会话（旧版本落盘）仍可被 scrubSecrets 清理", () => {
    // R144 起 save() 落盘前已除密，新会话不会再有明文；这里直写 localStorage
    // 模拟旧版本遗留数据，scrubSecrets 的清理职责仍然有效。
    localStorage.setItem(
      "andagent.session.s_legacy",
      JSON.stringify([{ role: "user", content: "帮我连 ssh password=abc123 谢谢" }]),
    );
    // scrubAll 按索引枚举会话：遗留数据也要在索引里登记一条才会被扫到。
    localStorage.setItem(
      "andagent.sessions.index.v1",
      JSON.stringify([{ id: "s_legacy", title: "legacy", updatedAt: 1, usage: { input: 0, output: 0 } }]),
    );
    const result = SessionStore.scrubSecrets();
    expect(result.replaced).toBeGreaterThan(0);

    const saved = JSON.stringify(SessionStore.load("s_legacy"));
    expect(saved).not.toContain("abc123");
    expect(saved).toContain("[REDACTED]");
    expect(saved).toContain("谢谢");
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

// R144 review 必修项①：save() 持久化路径本身必须除密——不依赖用户手动点
// 「除密」按钮，凭据（如 mqtt_pub/mqtt_sub 的 password）落盘前一律替换。
describe("SessionStore.save persists scrubbed messages (R144)", () => {
  it("工具调用参数里的 password 落盘前被替换为 [REDACTED]", () => {
    SessionStore.save("s_mqtt", [
      { role: "user", content: "连一下 MQTT" },
      {
        role: "assistant",
        content: "",
        tool_calls: [
          {
            function: {
              name: "mqtt_pub",
              arguments: {
                broker: "tcp://192.168.1.10:1883",
                topic: "home/light",
                payload: "on",
                username: "admin",
                password: "s3cr3t",
              },
            },
          },
        ],
      },
    ]);
    const saved = JSON.stringify(SessionStore.load("s_mqtt"));
    expect(saved).not.toContain("s3cr3t");
    expect(saved).toContain("[REDACTED]");
    // R144 六轮必修项①：username 与 password 是成对凭据，值一并替换。
    expect(saved).not.toContain("admin");
    // 非敏感字段保持原样（broker/topic 不受影响）。
    expect(saved).toContain("tcp://192.168.1.10:1883");
    expect(saved).toContain("home/light");
  });

  it("没有敏感内容的会话保存后逐字节不变", () => {
    const messages = [{ role: "user", content: "看看天气" }];
    SessionStore.save("s_plain", messages);
    expect(localStorage.getItem("andagent.session.s_plain")).toBe(JSON.stringify(messages));
  });

  // R144 review 三轮必修项①：broker userinfo（tcp://user:pass@host）这类
  // 字符串值不含 password 键名，必须由 URL userinfo 正则兜底除密。
  it("broker 参数携带 userinfo（tcp://user:pass@host）落盘前被遮盖", () => {
    SessionStore.save("s_userinfo", [
      { role: "user", content: "连一下 MQTT" },
      {
        role: "assistant",
        content: "",
        tool_calls: [
          {
            function: {
              name: "mqtt_pub",
              arguments: {
                broker: "tcp://admin:pw123@192.168.1.10:1883",
                topic: "home/light",
                payload: "on",
              },
            },
          },
        ],
      },
    ]);
    const saved = JSON.stringify(SessionStore.load("s_userinfo"));
    expect(saved).not.toContain("pw123");
    expect(saved).not.toContain("admin:pw123");
    expect(saved).toContain("tcp://<masked>@192.168.1.10:1883");
    expect(saved).toContain("home/light");
  });

  it("消息文本与标题里的 https URL userinfo 一并遮盖", () => {
    SessionStore.save("s_https", [
      { role: "user", content: "打开 https://alice:topsecret@files.example.com/下载看看" },
    ]);
    const saved = JSON.stringify(SessionStore.load("s_https"));
    expect(saved).not.toContain("topsecret");
    expect(saved).toContain("https://<masked>@files.example.com");
    // 索引标题同样不含明文（标题脱敏走同一条正则链）。
    const meta = SessionStore.list().find((s) => s.id === "s_https");
    expect(meta?.title).not.toContain("topsecret");
  });

  it("已落盘的旧明文标题在再次 save 时被除密", () => {
    // 直写索引模拟旧版本遗留：标题里带明文密码。
    const index = [{ id: "s_oldtitle", title: "连一下 password=abc123", updatedAt: 1, usage: { input: 0, output: 0 } }];
    localStorage.setItem("andagent.sessions.index.v1", JSON.stringify(index));
    localStorage.setItem("andagent.session.s_oldtitle", JSON.stringify([{ role: "user", content: "hi" }]));
    SessionStore.save("s_oldtitle", [{ role: "user", content: "hi" }]);
    const savedIndex = localStorage.getItem("andagent.sessions.index.v1") ?? "";
    expect(savedIndex).not.toContain("abc123");
    expect(savedIndex).toContain("[REDACTED]");
  });

  it("userinfo 遮盖幂等：重复 scrub 不产生叠加污染", () => {
    SessionStore.save("s_idem", [
      { role: "user", content: "mqtt_pub broker=tcp://bob:secret2@10.0.0.5:1883" },
    ]);
    SessionStore.scrubSecrets();
    SessionStore.scrubSecrets();
    const saved = localStorage.getItem("andagent.session.s_idem") ?? "";
    expect(saved).not.toContain("secret2");
    expect(saved).toContain("tcp://<masked>@10.0.0.5:1883");
    expect(saved).not.toContain("<masked>@<masked>");
  });

  // R144 review 三轮必修项②③：review 点名的三个边界——普通 URL 路径 @ 不误伤、
  // 无冒号/多 @ userinfo 全遮盖到最后一个 @、rename() 落盘入口同样除密。
  it("普通 URL 路径里的 @ 不被误伤", () => {
    SessionStore.save("s_plain_url", [
      { role: "user", content: "看 https://example.com:443/docs/@alice 和 tcp://10.0.0.9:1883" },
    ]);
    const saved = localStorage.getItem("andagent.session.s_plain_url") ?? "";
    expect(saved).toContain("https://example.com:443/docs/@alice");
    expect(saved).toContain("tcp://10.0.0.9:1883");
    expect(saved).not.toContain("<masked>");
  });

  it("无冒号与多 @ 的 userinfo 全遮盖到最后一个 @", () => {
    SessionStore.save("s_atforms", [
      {
        role: "user",
        content: "tcp://alice@broker:1883 和 tcp://admin:p@ss@broker:1883 和 tcp://admin:p@ss:word@broker:1883",
      },
    ]);
    const saved = localStorage.getItem("andagent.session.s_atforms") ?? "";
    expect(saved).not.toContain("alice@");
    expect(saved).not.toContain("admin:");
    expect(saved).not.toContain("p@ss");
    expect(saved).not.toContain("ss:word");
    // 三处 userinfo 全部整段遮盖，无残留（四轮 review 反例锁定）。
    expect((saved.match(/<masked>/g) ?? []).length).toBe(3);
    expect(saved).not.toContain("<masked>@ss");
    expect((saved.match(/tcp:\/\/<masked>@broker:1883/g) ?? []).length).toBe(3);
  });

  it("rename() 落盘标题同样除密", () => {
    SessionStore.save("s_rename", [{ role: "user", content: "hi" }]);
    SessionStore.rename("s_rename", "tcp://eve:hush@broker:1883");
    const savedIndex = localStorage.getItem("andagent.sessions.index.v1") ?? "";
    expect(savedIndex).not.toContain("hush");
    expect(savedIndex).toContain("tcp://<masked>@broker:1883");
  });
});

// R144 修3：migrateToKv 明文路径——解析失败的 localStorage 旧记录绝不写进 KV，
// 索引标题无条件脱敏，且解析失败条目从迁移索引剔除。
describe("SessionStore.migrateToKv 明文路径 (R144)", () => {
  it("parse 失败的 localStorage 记录不写 KV，索引标题脱敏并剔除该条", () => {
    const store = new Map<string, string>();
    const kvCall = (name: string, raw: string): string => {
      const args = JSON.parse(raw) as { namespace?: string; key?: string; value_json?: string };
      const skey = `${args.namespace ?? "default"}/${args.key}`;
      if (name === "kv_get") {
        const hit = store.get(skey);
        return hit === undefined
          ? JSON.stringify({ found: false })
          : JSON.stringify({ found: true, value: JSON.parse(hit) });
      }
      if (name === "kv_set") {
        store.set(skey, args.value_json ?? "");
        return JSON.stringify({ ok: true });
      }
      return JSON.stringify({ ok: true });
    };
    window.AndroidBridge = { kvCall } as unknown as NonNullable<Window["AndroidBridge"]>;
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

    // 解析失败的旧记录：非 JSON 明文（旧版本可能留存的凭据副本）。
    localStorage.setItem("andagent.session.s_bad", "RAW PLAINTEXT password=hunter2 {{{");
    // 可正常解析的记录（其标题带明文，用于验证索引标题无条件脱敏）。
    localStorage.setItem("andagent.session.s_ok", JSON.stringify([{ role: "user", content: "hi" }]));
    localStorage.setItem(
      "andagent.sessions.index.v1",
      JSON.stringify([
        { id: "s_bad", title: "坏 password=badtitle", updatedAt: 2, usage: { input: 0, output: 0 } },
        { id: "s_ok", title: "好 password=oktitle", updatedAt: 1, usage: { input: 0, output: 0 } },
      ]),
    );

    const list = SessionStore.list();

    // ① 解析失败记录绝不写进 KV：无 s_bad 会话体写入，也无明文副本。
    expect(store.has("sessions/s_bad")).toBe(false);
    expect([...store.values()].some((v) => v.includes("hunter2"))).toBe(false);
    expect(warn.mock.calls.some((c) => String(c[0]).includes("s_bad"))).toBe(true);
    // ② 解析失败条目从迁移索引剔除。
    expect(list.some((s) => s.id === "s_bad")).toBe(false);
    const kvIndexRaw = store.get("sessions/index");
    expect(kvIndexRaw).toBeDefined();
    const kvIndex = JSON.parse(JSON.parse(kvIndexRaw!) as string) as { id: string; title: string }[];
    expect(kvIndex.some((m) => m.id === "s_bad")).toBe(false);
    // ③ 索引标题无条件脱敏。
    expect(kvIndexRaw).not.toContain("oktitle");
    expect(kvIndexRaw).not.toContain("badtitle");
    expect(kvIndexRaw).toContain("[REDACTED]");
    expect(list.find((s) => s.id === "s_ok")?.title).toContain("[REDACTED]");
    warn.mockRestore();
  });
});

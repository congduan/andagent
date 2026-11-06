import { beforeEach, describe, expect, it } from "vitest";
import { SessionStore } from "./sessions";

type Bridge = NonNullable<Window["AndroidBridge"]>;

const INDEX_KEY = "andagent.sessions.index.v1";
const sessionKey = (id: string) => `andagent.session.${id}`;
const kvKey = (id: string) => `sessions/${id.startsWith("s_") ? id : `s_${id}`}`;

/** 最小 KV 桥：模拟 kv_get/kv_set/kv_delete，可注入写失败（64KB 上限）。 */
function installKvBridge(
  store = new Map<string, string>(),
  shouldFail: (key: string) => boolean = () => false,
): Map<string, string> {
  const kvCall = (name: string, raw: string): string => {
    const args = JSON.parse(raw) as { namespace?: string; key?: string; value_json?: string };
    const ns = args.namespace ?? "default";
    const skey = `${ns}/${args.key}`;
    switch (name) {
      case "kv_get": {
        const hit = store.get(skey);
        if (hit === undefined) return JSON.stringify({ found: false, namespace: ns, key: args.key });
        return JSON.stringify({ found: true, namespace: ns, key: args.key, value: JSON.parse(hit) });
      }
      case "kv_set": {
        if (args.key && shouldFail(args.key)) return "ERROR: 值超过 64KB 上限（sessions）";
        if (args.value_json === undefined) return "ERROR: 缺少 value_json";
        store.set(skey, args.value_json);
        return JSON.stringify({ ok: true, namespace: ns, key: args.key });
      }
      case "kv_delete": {
        const existed = store.delete(skey);
        if (!existed) return `ERROR: 键不存在：${ns}:${args.key}`;
        return JSON.stringify({ deleted: true, namespace: ns, key: args.key });
      }
      default:
        return `ERROR: 未知 KV 工具 ${name}`;
    }
  };
  window.AndroidBridge = { kvCall } as unknown as Bridge;
  return store;
}

const msgs = (text: string) => [{ role: "user", content: text }, { role: "assistant", content: "ok" }];

beforeEach(() => {
  localStorage.clear();
  delete window.AndroidBridge;
});

describe("SessionStore KV 适配", () => {
  it("KV 可用时，保存走 KV 且不写 localStorage", () => {
    const kv = installKvBridge();
    const list = SessionStore.save("s_test1", msgs("你好 KV"));

    expect(kv.has("sessions/index")).toBe(true);
    expect(kv.has(kvKey("s_test1"))).toBe(true);
    // 存的是 value_json（JSON 字符串），对端 kv_get 会解析成原字符串
    expect(JSON.parse(JSON.parse(kv.get(kvKey("s_test1"))!))).toEqual(msgs("你好 KV"));
    expect(localStorage.getItem(INDEX_KEY)).toBeNull();
    expect(list[0].id).toBe("s_test1");
    expect(SessionStore.load("s_test1")).toEqual(msgs("你好 KV"));
  });

  it("桥不可用时回退 localStorage（浏览器预览 / npm test）", () => {
    SessionStore.save("s_web", msgs("浏览器"));
    expect(JSON.parse(localStorage.getItem(INDEX_KEY)!)[0].id).toBe("s_web");
    expect(JSON.parse(localStorage.getItem(sessionKey("s_web"))!)).toEqual(msgs("浏览器"));
    expect(SessionStore.load("s_web")).toEqual(msgs("浏览器"));
  });

  it("KV 写失败（超 64KB）时镜像 localStorage，消息不丢", () => {
    installKvBridge(new Map(), (key) => key.startsWith("s_"));
    SessionStore.save("s_big", msgs("很长很长"));
    expect(SessionStore.load("s_big")).toEqual(msgs("很长很长"));
    expect(JSON.parse(localStorage.getItem(sessionKey("s_big"))!)).toEqual(msgs("很长很长"));
    // 索引仍进 KV，列表不会漏掉这条会话
    expect(SessionStore.list().some((s) => s.id === "s_big")).toBe(true);
  });

  it("首次读取把 localStorage 会话复制进 KV，且不删除源键", () => {
    const meta = [{ id: "s_old", title: "旧会话", updatedAt: 1 }];
    localStorage.setItem(INDEX_KEY, JSON.stringify(meta));
    localStorage.setItem(sessionKey("s_old"), JSON.stringify(msgs("旧消息")));

    const kv = installKvBridge();
    const list = SessionStore.list();

    expect(list[0].id).toBe("s_old");
    expect(kv.has(kvKey("s_old"))).toBe(true);
    expect(kv.has("sessions/index")).toBe(true);
    // 保守迁移：原始 localStorage 键保留
    expect(localStorage.getItem(INDEX_KEY)).not.toBeNull();
    expect(localStorage.getItem(sessionKey("s_old"))).not.toBeNull();
    expect(SessionStore.load("s_old")).toEqual(msgs("旧消息"));
  });

  it("KV 模式下删除会话会同时清 KV 与 localStorage", () => {
    const kv = installKvBridge();
    SessionStore.save("s_del", msgs("待删"));
    localStorage.setItem(sessionKey("s_del"), JSON.stringify(msgs("待删")));

    const left = SessionStore.remove("s_del");

    expect(left).toEqual([]);
    expect(kv.has(kvKey("s_del"))).toBe(false);
    expect(localStorage.getItem(sessionKey("s_del"))).toBeNull();
  });

  it("removeMany 双清会话体，并只持久化一次剩余索引", () => {
    const kv = installKvBridge();
    SessionStore.save("s_a", msgs("A"));
    SessionStore.save("s_b", msgs("B"));
    SessionStore.save("s_c", msgs("C"));
    localStorage.setItem(sessionKey("s_a"), JSON.stringify(msgs("A")));
    localStorage.setItem(sessionKey("s_b"), JSON.stringify(msgs("B")));
    let indexWrites = 0;
    const originalCall = window.AndroidBridge!.kvCall!;
    window.AndroidBridge!.kvCall = (name, raw) => {
      if (name === "kv_set" && JSON.parse(raw).key === "index") indexWrites++;
      return originalCall(name, raw);
    };

    const left = SessionStore.removeMany(["s_a", "s_b", "s_a"]);

    expect(left.map((s) => s.id)).toEqual(["s_c"]);
    expect(SessionStore.list().map((s) => s.id)).toEqual(["s_c"]);
    expect(JSON.parse(JSON.parse(kv.get("sessions/index")!)).map((s: { id: string }) => s.id)).toEqual(["s_c"]);
    expect(indexWrites).toBe(1);
    for (const id of ["s_a", "s_b"]) {
      expect(kv.has(kvKey(id))).toBe(false);
      expect(localStorage.getItem(sessionKey(id))).toBeNull();
    }
    expect(kv.has(kvKey("s_c"))).toBe(true);
  });
});

import WebSocket from "ws";
const ws = new WebSocket("ws://127.0.0.1:9223/devtools/page/F16765EAE23E21CF7018D60DC7E3C849");
let id = 0;
const send = (method, params={}) => new Promise((res) => {
  const i = ++id;
  const h = (m) => { const d = JSON.parse(m); if (d.id === i) { ws.off("message", h); res(d.result); } };
  ws.on("message", h);
  ws.send(JSON.stringify({ id: i, method, params }));
});
ws.on("open", async () => {
  await send("Runtime.enable");
  const expr = `JSON.stringify({
    hasBridge: typeof window.AndroidBridge !== "undefined",
    hasHubCall: typeof window.AndroidBridge?.resource_hub_call === "function",
    hasKvSetting: typeof window.AndroidBridge?.kvSettingCall === "function",
    hasToken: typeof window.__kvSettingToken === "string" && window.__kvSettingToken.length > 0,
    tokenLen: (window.__kvSettingToken || "").length,
    ua: navigator.userAgent.slice(0, 80)
  })`;
  const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true });
  console.log("STATE:", r.result.value);
  const expr2 = `(function(){
    try {
      const t = window.__kvSettingToken;
      if (!t) return "NO_TOKEN";
      const raw = window.AndroidBridge.resource_hub_call(JSON.stringify({ action: "list", __token: t }));
      return raw.slice(0, 300);
    } catch (e) { return "EXC:" + e.message; }
  })()`;
  const r2 = await send("Runtime.evaluate", { expression: expr2, returnByValue: true });
  console.log("HUB_LIST:", r2.result.value);
  ws.close(); process.exit(0);
});

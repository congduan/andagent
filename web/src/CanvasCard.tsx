import { useI18n } from "./i18n";
import { useEffect, useMemo, useRef, useState } from "react";
import { kvCall } from "./bridge";

/**
 * ```canvas fence: scripted 2D drawing inside a sandboxed iframe.
 *
 * The drawing code is untrusted (it comes from the model), so it never runs in
 * the app's WebView context: a `sandbox="allow-scripts"` iframe has an opaque
 * origin and cannot reach AndroidBridge, localStorage or the parent DOM.
 *
 * Fence payload is either plain JS source or a JSON object:
 *   { "width": 640, "height": 360, "animate": true, "code": "...", "title": "..." }
 * The script receives (ctx, t, w, h) where t is seconds since start.
 */
type CanvasSpec = {
  width: number;
  height: number;
  animate: boolean;
  code: string;
  title?: string;
};

const SANDBOX_DOC = `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;background:#0b0d11;overflow:hidden}
canvas{display:block;width:100%;height:100%}
</style></head><body><canvas id="c"></canvas><script>
var canvas=document.getElementById('c'),ctx=canvas.getContext('2d');
var spec=null,started=0,raf=0;
// ===== andagent 全局对象（统一 JS 命名空间）=====
// sandbox 是 opaque origin，拿不到 AndroidBridge——通过 postMessage RPC 代理到
// 父页（CanvasCard 转发 kvCall）。状态分两层：
//   andagent.state  围栏内存级（同一 iframe 生命周期跨帧保留，canvas 动画/live 累计数据）
//   andagent.kv     原生 KV 持久化（异步，走父页桥）
var rpcSeq=0, rpcPending={};
addEventListener('message',function(ev){
  var d=ev.data||{};
  if(d.type==='canvas'){ handleCanvas(d); return; }
  if(d.type==='andagent-rpc-result'){
    var pending=rpcPending[d.seq];
    if(pending){ delete rpcPending[d.seq]; pending(d.value); }
  }
});
function rpc(tool,args){
  return new Promise(function(resolve){
    var seq=++rpcSeq;
    rpcPending[seq]=resolve;
    parent.postMessage({type:'andagent-rpc',tool:tool,args:args,seq:seq},'*');
    setTimeout(function(){ if(rpcPending[seq]){ delete rpcPending[seq]; resolve(null); } },5000);
  });
}
var andagent={
  state:{},
  kv:{
    get:function(ns,key){ return rpc('kv_get',{namespace:ns||'default',key:key}).then(function(r){ return (r&&r.found)?r.value:null; }); },
    set:function(ns,key,value,ttlMs){ var a={namespace:ns||'default',key:key,value_json:JSON.stringify(value)}; if(ttlMs!==undefined&&ttlMs!==null)a.ttl_ms=ttlMs; return rpc('kv_set',a); },
    delete:function(ns,key){ return rpc('kv_delete',{namespace:ns||'default',key:key}); }
  }
};
window.andagent=andagent;
// ===== canvas 渲染管线 =====
// 显示区尺寸驱动内部缓冲：canvas 内部分辨率始终跟随 iframe 实际渲染尺寸，
// 绘图坐标 w/h 即显示区大小——绘图内容自动铺满，不再有未绘制的黑色空区。
function fit(){
  var r=canvas.getBoundingClientRect();
  var w=Math.max(64,Math.round(r.width)),h=Math.max(64,Math.round(r.height));
  if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;return true;}
  return false;
}
function handleCanvas(d){
  cancelAnimationFrame(raf);
  spec=d.spec;
  started=Date.now();
  frame();
}
function frame(){
  if(!spec)return;
  if(fit()){started=Date.now();cancelAnimationFrame(raf);}
  var t=(Date.now()-started)/1000;
  ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);
  try{ new Function('ctx','t','w','h','data','andagent',spec.code)(ctx,t,canvas.width,canvas.height,spec.data,andagent); }
  catch(e){ ctx.fillStyle='#ff9c9c';ctx.font='14px sans-serif';ctx.fillText(String(e),12,24); }
  ctx.restore();
  if(spec.animate)raf=requestAnimationFrame(frame);
}
addEventListener('resize',function(){ if(spec)frame(); });
frame();
</script></body></html>`;

function parseSpec(code: string): CanvasSpec | null {
  const trimmed = code.trim();
  if (trimmed.startsWith("{")) {
    try {
      const raw = JSON.parse(trimmed) as Record<string, unknown>;
      const body = typeof raw.code === "string" ? raw.code : "";
      if (!body) return null;
      return {
        width: clampDimension(raw.width, 640),
        height: clampDimension(raw.height, 360),
        animate: raw.animate === true,
        code: body,
        title: typeof raw.title === "string" ? raw.title : undefined,
      };
    } catch {
      return null;
    }
  }
  return { width: 640, height: 360, animate: /\bt\b|requestAnimationFrame/.test(trimmed), code: trimmed };
}

function clampDimension(value: unknown, fallback: number): number {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? Math.min(2048, Math.max(64, Math.round(n))) : fallback;
}

export function CanvasCard({ code, data }: { code: string; data?: unknown }) {
  const { t } = useI18n();
  const spec = useMemo(() => parseSpec(code), [code]);
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [runNonce, setRunNonce] = useState(0);
  // R67: 重新执行——srcDoc 重新挂载（runNonce 变化）让 iframe 从零重跑代码。
  const rerun = () => setRunNonce((v) => v + 1);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame || !spec || frame.dataset.ready !== "1") return;
    frame.contentWindow?.postMessage({ type: "canvas", spec: { ...spec, data } }, "*");
  }, [spec, data, visible, runNonce]);

  // andagent 全局对象 RPC 代理：sandbox iframe 的 andagent.kv.* → 这里转发到
  // 原生 KV 通道（kvCall）。异步结果以 andagent-rpc-result 回传给 iframe。
  useEffect(() => {
    const onRpc = (ev: MessageEvent) => {
      const d = ev.data as { type?: string; tool?: string; args?: Record<string, unknown>; seq?: number };
      if (d?.type !== "andagent-rpc" || !d.seq || typeof d.tool !== "string") return;
      const value = kvCall(d.tool, d.args ?? {});
      frameRef.current?.contentWindow?.postMessage({ type: "andagent-rpc-result", seq: d.seq, value: value ?? null }, "*");
    };
    window.addEventListener("message", onRpc);
    return () => window.removeEventListener("message", onRpc);
  }, []);

  const onFrameLoad = () => {
    const frame = frameRef.current;
    if (!frame || !spec) return;
    frame.dataset.ready = "1";
    frame.contentWindow?.postMessage({ type: "canvas", spec: { ...spec, data } }, "*");
  };

  if (!spec) return <div className="rich-error">{t("canvasInvalid")}</div>;
  if (!visible) return <div className="canvas-card"><button type="button" className="webview-toggle" onClick={() => setVisible(true)}>{t("showCanvas")}</button></div>;

  return (
    <div className={`canvas-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🎨 {spec.title ?? `Canvas ${spec.width}×${spec.height}`}</span>
        <button type="button" onClick={rerun}>{t("rerun")}</button>
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? t("exitFullscreen") : t("fullscreen")}</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible(false); }}>{t("close")}</button>
      </div>
      <iframe
        key={runNonce}
        ref={frameRef}
        title={spec.title ?? t("canvasDrawing")}
        sandbox="allow-scripts"
        srcDoc={SANDBOX_DOC}
        onLoad={onFrameLoad}
      />
    </div>
  );
}

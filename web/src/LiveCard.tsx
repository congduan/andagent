import { useI18n } from "./i18n";
import { useCallback, useEffect, useMemo, useState } from "react";
import { execTool } from "./bridge";
import { CanvasCard } from "./CanvasCard";
import { showError } from "./toast";

type LiveSpec = { name: string; code: string; interval_ms?: number; render?: string; width?: number; height?: number };
type LiveError = { message: string; stack?: string; scriptName?: string; line?: number; col?: number };
type LiveErrorEntry = { tick: number; timestamp: string; error: LiveError };
type LiveState = { id: string; name: string; state: string; ticks: number; failures: number; error?: string; warning?: string; errorHistory?: LiveErrorEntry[]; results: unknown[] };

const locationOf = (error: LiveError) => error.scriptName && error.line != null
  ? `${error.scriptName}:${error.line}:${error.col ?? 1}` : null;
const labelOf = (entry: LiveErrorEntry) => {
  const location = locationOf(entry.error);
  return `#${entry.tick}${location ? ` · ${location}` : ""} · ${entry.error.message}`;
};

const DEFAULT_RENDER = `
ctx.fillStyle='#0b0d11';ctx.fillRect(0,0,w,h);
ctx.fillStyle='#7dd3fc';ctx.font='20px sans-serif';ctx.fillText('Live',20,32);
ctx.fillStyle='#e5e7eb';ctx.font='16px monospace';
var text=JSON.stringify(data===undefined?null:data,null,2);
text.split('\\n').slice(0,14).forEach(function(line,i){ctx.fillText(line.slice(0,75),20,62+i*20);});
`;

export function LiveCard({ code }: { code: string }) {
  const { t } = useI18n();
  const spec = useMemo(() => {
    try {
      const parsed = JSON.parse(code) as LiveSpec;
      return typeof parsed.name === "string" && typeof parsed.code === "string" ? parsed : null;
    } catch { return null; }
  }, [code]);
  const [id, setId] = useState<string | null>(null);
  const [state, setState] = useState<LiveState | null>(null);
  const [busy, setBusy] = useState(false);

  const poll = useCallback(async () => {
    const result = await execTool("live_list", {});
    if (!result.ok) return;
    try {
      const loops = (JSON.parse(result.result) as { loops: LiveState[] }).loops;
      const found = loops.find((loop) => loop.id === id);
      // 循环已被原生侧移除（停止 / 连续失败自动停止）时，卡片也要落到 stopped，
      // 否则「停止」按钮会一直挂着，点了只是报错——看起来就是停止不了。
      if (found) setState(found);
      else setState((previous) => (previous ? { ...previous, state: "stopped" } : previous));
    } catch { /* show last valid frame */ }
  }, [id]);

  useEffect(() => {
    if (!id) return;
    let active = true;
    const tick = async () => { if (active) await poll(); };
    void tick();
    const timer = window.setInterval(() => void tick(), Math.max(250, spec?.interval_ms ?? 1000));
    return () => { active = false; window.clearInterval(timer); };
  }, [id, poll, spec?.interval_ms]);

  const action = async (name: string, args: object) => {
    setBusy(true);
    try {
      const result = await execTool(name, args);
      if (!result.ok || result.result.startsWith("ERROR:")) throw new Error(result.result);
      const next = JSON.parse(result.result) as LiveState;
      if (name === "live_start") setId(next.id);
      if (name === "live_stop") setState((previous) => (previous ? { ...previous, state: "stopped" } : previous));
      else setState(next);
    } catch (e) {
      showError(String(e));
      // 停止/暂停失败（循环已经不在了等）：立刻刷新真实状态，别让按钮继续骗人。
      if (name === "live_stop" || name === "live_pause") void poll();
    }
    finally { setBusy(false); }
  };

  // 轮询到的循环错误也统一走顶部 toast（同一条只弹一次）。
  const loopError = state?.error;
  useEffect(() => {
    if (loopError) showError(loopError);
  }, [loopError]);

  if (!spec) return <div className="rich-error">{t("liveInvalid")}</div>;
  const data = state?.results?.at(-1);
  const canvasCode = JSON.stringify({ width: spec.width ?? 640, height: spec.height ?? 360,
    title: spec.name, code: spec.render ?? DEFAULT_RENDER });
  return <div className="live-card">
    <div className="webview-bar">
      <span className="webview-uri">● {spec.name} {state ? `· ${state.state} · ${state.ticks} tick` : ""}</span>
      {(!id || state?.state === "stopped") && <button type="button" disabled={busy} onClick={() => void action("live_start", {
        name: spec.name, code: spec.code, interval_ms: spec.interval_ms ?? 1000, consumer: "dialog",
      })}>{id ? t("restart") : t("startLoop")}</button>}
      {id && state?.state !== "stopped" && <>
        <button type="button" disabled={busy} onClick={() => void action("live_pause", { id, paused: state?.state !== "paused" })}>
          {state?.state === "paused" ? t("resume") : t("pause")}</button>
        <button type="button" disabled={busy} onClick={() => void action("live_stop", { id })}>{t("stopAction")}</button>
      </>}
    </div>
    {(state?.errorHistory?.length || state?.error) ? <div className="live-errors">
      <div className="live-error-current" role="alert">{state?.errorHistory?.[0] ? labelOf(state.errorHistory[0]) : state?.error}</div>
      {!!state?.errorHistory?.length && <details>
        <summary>最近错误 ({state.errorHistory.length})</summary>
        <ol>{state.errorHistory.map((entry, index) => <li key={`${entry.tick}-${index}`}>
          <span className="live-error-label">{labelOf(entry)}</span>
          {entry.error.stack && <pre className="live-error-stack">{entry.error.stack}</pre>}
        </li>)}</ol>
      </details>}
    </div> : null}
    <CanvasCard code={canvasCode} data={data} />
  </div>;
}

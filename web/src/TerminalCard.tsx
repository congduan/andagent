import { translate, useI18n } from "./i18n";
import { useEffect, useRef, useState } from "react";
import { lastShellResult, termuxTerminalExec, termuxTerminalPoll } from "./bridge";
import { ImageLightbox } from "./ImageLightbox";

/**
 * ```terminal fence: an in-conversation interactive terminal (R27).
 *
 * Commands run in the same long-lived embedded Termux bash session that
 * shell_exec uses (cd/env state shared). There is no PTY in v1 — stdin is a
 * pipe, so bash does not echo; the card echoes the submitted line locally and
 * polls incremental output every 200ms. ANSI escapes are stripped (plain text
 * is enough for manual command running in v1).
 */

const HISTORY_KEY = "andagent.terminal.history.v1";
const HISTORY_MAX = 100;
// CSI 序列 + OSC（窗口标题等）一并剥掉。
const ANSI_RE = /\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g;
// terminalExec 附加的弱 marker：`__ANDAGENT_TTY_<n>:<exit>`，转成退出码提示行。
const TTY_MARKER_RE = /^__ANDAGENT_TTY_\d+:(-?\d+)\s*$/;

function loadHistory(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? "[]");
    return Array.isArray(raw) ? raw.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

function saveHistory(history: string[]): void {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(-HISTORY_MAX))); } catch { /* ignore */ }
}

export function TerminalCard({ code }: { code: string }) {
  const { t } = useI18n();
  let title = translate("terminalTitle");
  try {
    const parsed = JSON.parse(code || "{}") as { title?: string };
    if (typeof parsed.title === "string" && parsed.title.trim()) title = parsed.title.trim();
  } catch { /* 内容非 JSON 时仍渲染终端 */ }

  const [lines, setLines] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [history, setHistory] = useState<string[]>(loadHistory);
  const historyIdx = useRef(-1);
  const [dead, setDead] = useState("");
  const [closed, setClosed] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [showAgentOutput, setShowAgentOutput] = useState(false);
  const pinned = useRef(true); // 用户上滚时暂停自动滚底
  const scrollRef = useRef<HTMLPreElement>(null);

  // 200ms 轮询增量输出；关闭或会话报错后停止。
  useEffect(() => {
    if (closed || dead) return;
    const timer = window.setInterval(() => {
      const out = termuxTerminalPoll();
      if (!out) return;
      const chunk = out
        .replace(ANSI_RE, "")
        .split("\n")
        .map((line) => {
          const marker = TTY_MARKER_RE.exec(line.trim());
          return marker ? `↳ exit ${marker[1]}` : line.replace(/\r/g, "");
        });
      setLines((prev) => {
        const merged = [...prev, ...chunk];
        return merged.length > 2000 ? merged.slice(-2000) : merged;
      });
    }, 200);
    return () => window.clearInterval(timer);
  }, [closed, dead]);

  // 自动滚底（用户上滚时暂停）。
  useEffect(() => {
    const node = scrollRef.current;
    if (node && pinned.current) node.scrollTop = node.scrollHeight;
  }, [lines, closed]);

  const submit = () => {
    const cmd = input;
    setInput("");
    if (!cmd.trim()) { setLines((prev) => [...prev, "$"]); return; }
    const nextHistory = [...history.filter((h) => h !== cmd), cmd].slice(-HISTORY_MAX);
    setHistory(nextHistory);
    saveHistory(nextHistory);
    historyIdx.current = -1;
    setLines((prev) => [...prev, `$ ${cmd}`]);
    pinned.current = true;
    const result = termuxTerminalExec(cmd);
    if (result.startsWith("ERROR:")) {
      setDead(result);
      setLines((prev) => [...prev, result]);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") { e.preventDefault(); submit(); return; }
    if (e.key === "ArrowUp" || e.key === "ArrowDown") {
      e.preventDefault();
      if (history.length === 0) return;
      let idx = historyIdx.current;
      if (e.key === "ArrowUp") idx = idx < 0 ? history.length - 1 : Math.max(0, idx - 1);
      else {
        if (idx < 0) return;
        idx += 1;
        if (idx >= history.length) { historyIdx.current = -1; setInput(""); return; }
      }
      historyIdx.current = idx;
      setInput(history[idx]);
    }
  };

  const onScroll = () => {
    const node = scrollRef.current;
    if (!node) return;
    pinned.current = node.scrollHeight - node.scrollTop - node.clientHeight < 48;
  };

  const body = (
    <>
      <pre className="terminal-scroll" ref={scrollRef} onScroll={onScroll}>
        {lines.map((line, i) => (
          <div key={i} className={line.startsWith("↳ exit") ? "terminal-exit" : undefined}>{line || " "}</div>
        ))}
        {!lines.length && <div className="terminal-idle">{t("terminalIdle")}</div>}
      </pre>
      <div className="terminal-input-row">
        <span className="terminal-prompt">$</span>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          disabled={!!dead}
          placeholder={dead ? t("sessionUnavailable") : t("enterCommand")}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
        />
        <button type="button" className="terminal-run" onClick={submit} disabled={!!dead}>{t("execute")}</button>
      </div>
    </>
  );

  return (
    <div className={`terminal-card${fullscreen ? " fullscreen" : ""}${closed ? " closed" : ""}`}>
      <div className="terminal-bar">
        <span className="terminal-title">⌨️ {title}</span>
        <button type="button" onClick={() => setShowAgentOutput(true)}>{t("assistantOutput")}</button>
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? t("exitFullscreen") : t("fullscreen")}</button>
        <button type="button" onClick={() => { setLines([]); pinned.current = true; }}>{t("clearScreen")}</button>
        <button type="button" onClick={() => { setClosed((v) => !v); }}>{closed ? t("reopen") : t("close")}</button>
      </div>
      {showAgentOutput && (
        <ImageLightbox label={t("shellOutput")} onClose={() => setShowAgentOutput(false)} zoomable={false}>
          <div className="mermaid-lightbox-body">
            <div className="tool-label">{t("lastShellResult")}</div>
            <pre className="tool-pre" style={{ maxHeight: "70vh" }}>{lastShellResult() || t("noShellResult")}</pre>
          </div>
        </ImageLightbox>
      )}
      {closed
        ? <div className="terminal-idle">{t("terminalClosed")}</div>
        : body}
      {dead && <div className="terminal-error">{dead}</div>}
    </div>
  );
}

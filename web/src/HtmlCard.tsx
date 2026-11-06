import { useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "./i18n";

/**
 * R94: ```html 围栏——直接在对话流里渲染 AI 生成的完整 HTML 文档。
 * 与 canvas（2D 绘图协议）不同，html 围栏按原样 iframe srcDoc 渲染，
 * 支持 CSS/JS 交互（沙箱 allow-scripts，同 canvas 安全模型：opaque origin，
 * 拿不到 AndroidBridge，事件不上抛）。
 */
export function HtmlCard({ code }: { code: string }) {
  const { t } = useI18n();
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [runNonce, setRunNonce] = useState(0);
  const frameRef = useRef<HTMLIFrameElement | null>(null);

  const doc = useMemo(() => {
    const trimmed = code.trim();
    // 已是完整文档直接用；片段则包一层最小文档并带移动 viewport。
    if (/<html[\s>]/i.test(trimmed)) return trimmed;
    return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{margin:12px;font:14px/1.6 system-ui,sans-serif;background:#0b0d11;color:#e7ecf5}</style></head><body>${trimmed}</body></html>`;
  }, [code]);

  // 卸载/隐藏时回收 iframe（与 CanvasCard 一致的生命周期语义）。
  useEffect(() => () => { if (frameRef.current) frameRef.current.src = "about:blank"; }, [visible]);

  if (!visible) {
    return (
      <div className="html-card">
        <button type="button" className="webview-toggle" onClick={() => setVisible(true)}>{t("showCanvas")}</button>
      </div>
    );
  }

  return (
    <div className={`html-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🧩 {t("htmlCard")}</span>
        <button type="button" onClick={() => setRunNonce((v) => v + 1)} title={t("rerun")}>↻</button>
        <button type="button" onClick={() => setFullscreen((v) => !v)} title={fullscreen ? t("exitFullscreen") : t("fullscreen")}>⛶</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible(false); }} title={t("close")}>✕</button>
      </div>
      <iframe
        key={runNonce}
        ref={frameRef}
        title={t("htmlCard")}
        sandbox="allow-scripts"
        srcDoc={doc}
      />
    </div>
  );
}

import { useI18n } from "./i18n";
import { useEffect, useRef, useState } from "react";
import { openEmbeddedWebview } from "./bridge";
import { showError } from "./toast";

/**
 * Web page card rendered inside the conversation flow (#8).
 *
 * It lives in the main WebView, so AndroidBridge (and therefore andagent.tool)
 * is inherited from the host page by same-origin assets. Cross-origin pages
 * cannot reach the bridge unless they explicitly opt in, and the iframe is
 * sandboxed to keep navigation from escaping the card.
 *
 * R101: for a local HTML file (web_embed local=true), route to the native
 * embedded WebView (openEmbeddedWebview -> stageLocalHtml). That WebView is a
 * same-process DevTools target: devtools_status can enumerate it and
 * devtools_eval / devtools_console / devtools_errors can inspect it, which is
 * the "generate web page -> embed -> check errors -> fix" loop. We auto-open it
 * once so calling web_embed immediately yields a debuggable page.
 */
export function WebViewCard({ uri, local }: { uri: string; local?: boolean }) {
  const { t } = useI18n();
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [nonce, setNonce] = useState(0);
  const allowed = /^https:\/\/[^/\s]+/i.test(uri);
  const openedRef = useRef(false);

  const openExternal = () => {
    const result = openEmbeddedWebview(uri);
    if (result.startsWith("ERROR:")) showError(result);
  };

  // 本地 HTML：自动在原生内嵌 WebView 打开一次（它才是可被 CDP 枚举/执行的页面）。
  useEffect(() => {
    if (local && !openedRef.current) {
      openedRef.current = true;
      try { openEmbeddedWebview(uri); } catch { /* 浏览器环境无设备桥，忽略 */ }
    }
  }, [local, uri]);

  if (local) {
    return (
      <div className="webview-card">
        <div className="webview-bar">
          <span className="webview-uri">🌐 {uri}</span>
          <button type="button" onClick={openExternal} title={t("openExternal")}>{t("externalWindow")}</button>
        </div>
        <div className="webview-uri" style={{ padding: 12 }}>
          本地 HTML 已在调试浏览器中打开：可用 devtools_console / devtools_errors 查加载期报错、devtools_eval 验证 DOM。修改文件后点「外部窗口」重新打开即可复查。
        </div>
      </div>
    );
  }

  if (!allowed) return <div className="webview-card"><div className="webview-uri">🌐 {uri}</div><div className="webview-error">{t("httpsOnly")}</div></div>;

  return (
    <div className={`webview-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🌐 {uri}</span>
        {visible && <button type="button" onClick={() => setNonce((v) => v + 1)} title={t("refresh")}>↻</button>}
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? t("exitFullscreen") : t("fullscreen")}</button>
        <button type="button" onClick={openExternal} title={t("openExternal")}>{t("externalWindow")}</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible((v) => !v); }}>{visible ? t("close") : t("expand")}</button>
      </div>
      {visible && (
        <iframe
          key={nonce}
          title={uri}
          src={uri}
          loading="lazy"
          referrerPolicy="no-referrer"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
        />
      )}
    </div>
  );
}

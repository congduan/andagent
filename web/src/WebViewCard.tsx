import { useI18n } from "./i18n";
import { useState } from "react";
import { openEmbeddedWebview } from "./bridge";
import { showError } from "./toast";

/**
 * Web page card rendered inside the conversation flow (#8).
 *
 * It lives in the main WebView, so AndroidBridge (and therefore andagent.tool)
 * is inherited from the host page by same-origin assets. Cross-origin pages
 * cannot reach the bridge unless they explicitly opt in, and the iframe is
 * sandboxed to keep navigation from escaping the card.
 */
export function WebViewCard({ uri }: { uri: string }) {
  const { t } = useI18n();
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [nonce, setNonce] = useState(0);
  const allowed = /^https:\/\/[^/\s]+/i.test(uri);

  const openExternal = () => {
    const result = openEmbeddedWebview(uri);
    if (result.startsWith("ERROR:")) showError(result);
  };

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

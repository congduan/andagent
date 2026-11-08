import { useI18n } from "./i18n";
import { useEffect, useRef, useState } from "react";
import { openEmbeddedWebview } from "./bridge";
import { showError } from "./toast";
import { Icon } from "./icons";

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
          <span className="webview-uri"><Icon name="globe" size={13} /> {uri}</span>
          <button type="button" onClick={openExternal} title={t("openExternal")}>{t("externalWindow")}</button>
        </div>
        <div className="webview-uri" style={{ padding: 12 }}>
          本地 HTML 已在调试浏览器中打开：可用 devtools_console / devtools_errors 查加载期报错、devtools_eval 验证 DOM。修改文件后点「外部窗口」重新打开即可复查。
        </div>
      </div>
    );
  }

  if (!allowed) return <div className="webview-card"><div className="webview-uri"><Icon name="globe" size={13} /> {uri}</div><div className="webview-error">{t("httpsOnly")}</div></div>;
  // R96 review 二轮必修项1：appassets 同源页禁止进卡片 iframe。该域与主 shell
  // 同源，iframe 带 allow-same-origin 时内部脚本可读 parent.__kvSettingToken
  // （设置通道主帧凭证）并继承 AndroidBridge 直调 kvSettingCall 读取 secure KV
  //（含 API key）——凭据永不过模型铁律。本地 HTML 展示请用 local=true 的
  // 原生内嵌 WebView（无 AndroidBridge，且 DevTools eval 已被安全策略拦）。
  // R96 review 三轮必修项1：字符串正则挡不住 `:443` 默认端口规范化（浏览器视
  // 其为同一 origin）——改用 URL 解析取 host（端口无关：该域非公网可路由，
  // 任何端口命中都拦，保守无副作用）；解析失败按危险处理（fail-closed）。
  const isShellOriginUri = (() => {
    try {
      return new URL(uri).hostname.toLowerCase() === "appassets.androidplatform.net";
    } catch {
      return true;
    }
  })();
  if (isShellOriginUri) {
    return (
      <div className="webview-card">
        <div className="webview-bar">
          <span className="webview-uri"><Icon name="globe" size={13} /> {uri}</span>
          <button type="button" onClick={openExternal} title={t("openExternal")}>{t("externalWindow")}</button>
        </div>
        <div className="webview-error">{t("appassetsEmbedBlocked")}</div>
      </div>
    );
  }
  return (
    <div className={`webview-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri"><Icon name="globe" size={13} /> {uri}</span>
        {visible && <button type="button" onClick={() => setNonce((v) => v + 1)} title={t("refresh")}><Icon name="refresh" size={13} /></button>}
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
          // R96 review 三轮必修项1（纵深）：撤掉 allow-same-origin——iframe 变
          // opaque origin，即便页面运行时把自己导航到同源 URL 也读不到
          // parent.__kvSettingToken（src 检查只管初始加载）。代价：远端页
          // Cookie/localStorage 失效，完整体验走「外部窗口」bridge-less WebView。
          sandbox="allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox"
        />
      )}
    </div>
  );
}

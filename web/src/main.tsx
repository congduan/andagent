import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { applyTheme, watchSystemTheme } from "./theme";
import { installKeyboardInset } from "./keyboard";
import { syncLanguageToNative } from "./i18n";
import "highlight.js/styles/github-dark.css";
import "katex/dist/katex.min.css";
import "./styles.css";
import App from "./App";

// Apply the persisted theme before first paint so startup has no flash.
applyTheme();
watchSystemTheme();
// 输入法弹出前先接管底部让位，首帧就不会把输入框压在键盘下。
installKeyboardInset();
// 原生工具结果/原生界面沿用同一语言（默认系统语言）。
syncLanguageToNative();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

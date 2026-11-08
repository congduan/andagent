import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { applyTheme, watchSystemTheme } from "./theme";
import { installKeyboardInset } from "./keyboard";
import { syncLanguageToNative } from "./i18n";
import "highlight.js/styles/github-dark.css";
import "katex/dist/katex.min.css";
import "./styles.css";
import App from "./App";

// 浏览器预览先装假桥，再跑首帧：设置、主题、语言都从假桥读，顺序反了会各读一半。
// 生产构建里 import.meta.env.DEV 为 false，整个分支（含动态 import）被摇掉。
async function bootstrap(): Promise<void> {
  if (import.meta.env.DEV && import.meta.env.VITE_ANDAGENT_MOCK_BRIDGE !== "0") {
    await import("./devMockBridge");
  }

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
}

void bootstrap();

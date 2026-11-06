/**
 * Day/night theme switching (R39).
 *
 * Three modes: "system" | "light" | "dark". The resolved theme is written to
 * document.documentElement.dataset.theme so CSS picks it via
 * `:root[data-theme="light"]`, persisted in localStorage, and reported to the
 * Kotlin side (WebView background / status bar) through the bridge so the
 * native chrome matches and startup doesn't flash the wrong color.
 */

export type ThemeMode = "system" | "light" | "dark";

const THEME_KEY = "andagent.theme.v1";
const MODES: ThemeMode[] = ["system", "light", "dark"];

declare global {
  interface Window {
    /** Kotlin 侧 uiMode 变化推送（系统主题真值，见下方 nativeSystemDark）。 */
    __andagentSystemTheme?: (dark: boolean) => void;
  }
}

/**
 * Android WebView 的 `prefers-color-scheme` 取自宿主 Activity 主题，而不是
 * 系统设置：本应用用的是深色 `android:Theme.Material.NoActionBar`，媒体查询
 * 因此恒报 dark——手机日间、UI 夜间就是这么来的。原生侧用
 * Configuration.uiMode 给出真值，优先采用；浏览器预览/测试环境没有 bridge
 * 时才退回媒体查询，且兜底为日间（Android 的默认取值）。
 */
function nativeSystemDark(): boolean | undefined {
  try {
    const value = window.AndroidBridge?.systemTheme?.();
    return value === "dark" ? true : value === "light" ? false : undefined;
  } catch {
    return undefined;
  }
}

const nativeDark = nativeSystemDark();
/** bridge 可用时以原生值为准，忽略不可信的媒体查询事件。 */
let usingNative = nativeDark !== undefined;
let systemDark = nativeDark ?? window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;

export function loadThemeMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    return MODES.includes(raw as ThemeMode) ? (raw as ThemeMode) : "system";
  } catch {
    return "system";
  }
}

export function resolvedTheme(mode: ThemeMode = loadThemeMode()): "light" | "dark" {
  if (mode === "system") return systemDark ? "dark" : "light";
  return mode;
}

/** Report the resolved theme to Kotlin (WebView background, status bar). */
function reportToNative(isDark: boolean): void {
  try {
    window.AndroidBridge?.reportTheme?.(isDark);
  } catch {
    /* bridge not connected (browser preview) */
  }
}

let themeTransitionTimer: number | undefined;

/**
 * 主题切换时给颜色加一小段过渡（约 300ms），避免整屏瞬间反色。
 * 只在真的切换配色时挂 class，启动首次 applyTheme 不会造成闪烁。
 */
function armThemeTransition(root: HTMLElement, resolved: "light" | "dark"): void {
  if (root.dataset.theme === resolved) return;
  root.classList.add("theme-transition");
  // 强制一次样式计算，确保过渡从旧配色开始。
  void root.offsetHeight;
  if (themeTransitionTimer !== undefined) window.clearTimeout(themeTransitionTimer);
  themeTransitionTimer = window.setTimeout(() => {
    root.classList.remove("theme-transition");
    themeTransitionTimer = undefined;
  }, 320);
}

/** Apply the current mode to the DOM and notify listeners. */
export function applyTheme(mode: ThemeMode = loadThemeMode()): "light" | "dark" {
  const resolved = resolvedTheme(mode);
  armThemeTransition(document.documentElement, resolved);
  document.documentElement.dataset.theme = resolved;
  let meta = document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]');
  if (!meta) {
    meta = document.createElement("meta");
    meta.name = "color-scheme";
    document.head.appendChild(meta);
  }
  meta.content = resolved;
  reportToNative(resolved === "dark");
  return resolved;
}

export function setTheme(mode: ThemeMode): void {
  try {
    localStorage.setItem(THEME_KEY, mode);
  } catch {
    /* storage unavailable */
  }
  applyTheme(mode);
  window.dispatchEvent(new CustomEvent("andagent:themechange", { detail: resolvedTheme(mode) }));
}

/** Toggle used by the drawer shortcut: light <-> dark (explicit mode). */
export function toggleTheme(): void {
  setTheme(resolvedTheme() === "dark" ? "light" : "dark");
}

/** 更新系统深浅色；仅在 "system" 模式下立刻重绘。 */
function setSystemDark(dark: boolean): void {
  if (systemDark === dark) return;
  systemDark = dark;
  if (loadThemeMode() === "system") {
    applyTheme("system");
    window.dispatchEvent(new CustomEvent("andagent:themechange", { detail: resolvedTheme("system") }));
  }
}

/**
 * Follow OS switches while mode === "system". Call once at startup.
 *
 * 原生 bridge 可用时由 Kotlin 在 uiMode 变化后调用
 * `window.__andagentSystemTheme(dark)`，此时必须忽略媒体查询（它在 WebView
 * 里不可信）。浏览器预览只有媒体查询可用。
 */
export function watchSystemTheme(): void {
  window.__andagentSystemTheme = (dark: boolean) => {
    usingNative = true;
    setSystemDark(dark);
  };
  window.matchMedia?.("(prefers-color-scheme: dark)").addEventListener?.("change", (e) => {
    if (usingNative) return;
    setSystemDark(e.matches);
  });
}

/** Test hook: force the cached system-dark value. */
export const __internal = {
  setSystemDark(dark: boolean) {
    usingNative = false;
    systemDark = dark;
    if (loadThemeMode() === "system") applyTheme("system");
  },
};

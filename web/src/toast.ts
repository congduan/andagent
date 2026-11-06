import { translate } from "./i18n";

/**
 * 顶部 toast（统一错误/状态提示）。
 *
 * 刻意做成不依赖 React 树的原生 DOM 单例：任何模块（含非组件代码与
 * 逐条渲染的卡片）都能直接调用，不必把状态层层上传到 App。挂载点固定在
 * body 上、层级高于设置/抽屉等覆盖层，所以无论当前在哪一页都从顶部滑入。
 */
export type ToastTone = "error" | "success" | "info";

export interface ToastOptions {
  tone?: ToastTone;
  /** 停留毫秒；0 表示手动关闭。默认 error 8s、其余 3.2s。 */
  durationMs?: number;
}

const MAX_VISIBLE = 3;
const DEFAULT_DURATION: Record<ToastTone, number> = {
  error: 8000,
  success: 3200,
  info: 3200,
};

let host: HTMLDivElement | null = null;
let seq = 0;
const timers = new Map<number, number>();

/** 上滑超过该距离即消除；小于它则回弹。 */
const SWIPE_DISMISS_PX = 48;
/** 判定手势方向前允许的抖动。 */
const SWIPE_SLOP_PX = 8;

function ensureHost(): HTMLDivElement {
  if (host?.isConnected) return host;
  host = document.createElement("div");
  host.className = "toast-host";
  document.body.appendChild(host);
  return host;
}

function removeNow(el: HTMLElement, id: number): void {
  const timer = timers.get(id);
  if (timer !== undefined) {
    window.clearTimeout(timer);
    timers.delete(id);
  }
  el.remove();
}

function clearTimer(id: number): void {
  const timer = timers.get(id);
  if (timer !== undefined) {
    window.clearTimeout(timer);
    timers.delete(id);
  }
}

export function dismissToast(id: number): void {
  clearTimer(id);
  const el = host?.querySelector<HTMLElement>(`[data-toast-id="${id}"]`);
  if (!el) return;
  el.classList.add("toast-out");
  window.setTimeout(() => el.remove(), 180);
}

/** 跟手滑出：不再走 toast-out（那只上移 8px），直接推出屏幕。 */
function swipeAway(el: HTMLElement, id: number): void {
  clearTimer(id);
  el.style.transition = "transform 0.18s ease-out, opacity 0.18s ease-out";
  el.style.transform = "translateY(-140%)";
  el.style.opacity = "0";
  window.setTimeout(() => el.remove(), 190);
}

/**
 * 上滑消除：拖动时跟手位移（只允许向上），松手时超过阈值则滑出，
 * 否则回弹。横向手势（文本选择、外部滑动手势）直接放弃，交给默认行为。
 */
function attachSwipe(el: HTMLElement, id: number): void {
  let startX = 0;
  let startY = 0;
  let offsetY = 0;
  let tracking = false;
  let swiped = false;
  let pointerId: number | null = null;

  const resetStyles = () => {
    el.style.transition = "";
    el.style.transform = "";
    el.style.opacity = "";
  };

  el.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (pointerId !== null) return;
    pointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
    offsetY = 0;
    tracking = true;
    swiped = false;
  });

  el.addEventListener("pointermove", (event) => {
    if (!tracking || event.pointerId !== pointerId) return;
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (!swiped) {
      if (Math.abs(dx) < SWIPE_SLOP_PX && Math.abs(dy) < SWIPE_SLOP_PX) return;
      if (Math.abs(dx) > Math.abs(dy)) {
        tracking = false;
        pointerId = null;
        return;
      }
      swiped = true;
      // 拖拽期间交给 inline transform，避免入场动画覆盖跟手位移。
      el.style.animation = "none";
      el.setPointerCapture(event.pointerId);
    }
    event.preventDefault();
    offsetY = Math.min(0, dy);
    el.style.transition = "none";
    el.style.transform = `translateY(${offsetY}px)`;
    el.style.opacity = String(Math.max(0.3, 1 + offsetY / 160));
  });

  const finish = (event: PointerEvent) => {
    if (!tracking || event.pointerId !== pointerId) return;
    tracking = false;
    const distance = offsetY;
    if (pointerId !== null && el.hasPointerCapture(pointerId)) {
      el.releasePointerCapture(pointerId);
    }
    pointerId = null;
    if (!swiped) return;
    if (distance <= -SWIPE_DISMISS_PX) {
      swipeAway(el, id);
      return;
    }
    el.style.transition = "transform 0.16s ease-out, opacity 0.16s ease-out";
    el.style.transform = "";
    el.style.opacity = "";
    window.setTimeout(() => {
      if (!tracking) resetStyles();
    }, 180);
  };

  el.addEventListener("pointerup", finish);
  el.addEventListener("pointercancel", finish);

  // 拖过之后浏览器仍会补发 click，拦掉以免回弹后又被当成点击关闭。
  el.addEventListener(
    "click",
    (event) => {
      if (!swiped) return;
      swiped = false;
      event.stopPropagation();
      event.preventDefault();
    },
    true,
  );
}

/** 显示一条顶部 toast，返回 id（重复内容会复用同一条并重新计时）。 */
export function showToast(message: string, options: ToastOptions = {}): number {
  const text = String(message ?? "").trim();
  if (!text) return -1;
  const tone = options.tone ?? "info";
  const node = ensureHost();

  // 同文案（例如轮询失败、重复点击）只保留一条，避免堆叠刷屏。
  const existing = Array.from(node.querySelectorAll<HTMLElement>(".toast")).find(
    (el) => el.dataset.toastTone === tone && el.dataset.toastText === text,
  );
  if (existing) {
    const id = Number(existing.dataset.toastId);
    existing.classList.remove("toast-out");
    existing.style.transition = "";
    existing.style.transform = "";
    existing.style.opacity = "";
    existing.style.animation = "";
    node.appendChild(existing);
    const prev = timers.get(id);
    if (prev !== undefined) window.clearTimeout(prev);
    const duration = options.durationMs ?? DEFAULT_DURATION[tone];
    if (duration > 0) timers.set(id, window.setTimeout(() => dismissToast(id), duration));
    else timers.delete(id);
    return id;
  }

  const id = ++seq;
  const el = document.createElement("div");
  el.className = `toast toast-${tone}`;
  el.dataset.toastId = String(id);
  el.dataset.toastTone = tone;
  el.dataset.toastText = text;
  el.setAttribute("role", tone === "error" ? "alert" : "status");

  const icon = document.createElement("span");
  icon.className = "toast-icon";
  icon.setAttribute("aria-hidden", "true");
  icon.textContent = tone === "error" ? "!" : tone === "success" ? "✓" : "i";

  const body = document.createElement("span");
  body.className = "toast-text";
  body.textContent = text;

  const close = document.createElement("button");
  close.type = "button";
  close.className = "toast-close";
  close.setAttribute("aria-label", translate("close"));
  close.textContent = "×";
  close.addEventListener("click", (event) => {
    event.stopPropagation();
    dismissToast(id);
  });

  el.append(icon, body, close);
  attachSwipe(el, id);
  el.addEventListener("click", () => dismissToast(id));
  node.appendChild(el);

  while (node.childElementCount > MAX_VISIBLE) {
    const first = node.firstElementChild as HTMLElement | null;
    if (!first) break;
    removeNow(first, Number(first.dataset.toastId));
  }

  const duration = options.durationMs ?? DEFAULT_DURATION[tone];
  if (duration > 0) timers.set(id, window.setTimeout(() => dismissToast(id), duration));
  return id;
}

export function showError(message: string, options: Omit<ToastOptions, "tone"> = {}): number {
  return showToast(message, { ...options, tone: "error" });
}

export function showSuccess(message: string, options: Omit<ToastOptions, "tone"> = {}): number {
  return showToast(message, { ...options, tone: "success" });
}

export function showInfo(message: string, options: Omit<ToastOptions, "tone"> = {}): number {
  return showToast(message, { ...options, tone: "info" });
}

/**
 * 把一段操作结果（bridge 约定失败为 "ERROR: ..."）转成 toast。
 * 成功文案也走 toast，保证各页面反馈一致。
 */
export function showResult(result: string, options: Omit<ToastOptions, "tone"> = {}): number {
  const text = String(result ?? "").trim();
  if (!text) return -1;
  return text.startsWith("ERROR:")
    ? showError(stripErrorPrefix(text), options)
    : showSuccess(text, options);
}

function stripErrorPrefix(message: string): string {
  return message.startsWith("ERROR:") ? message.slice("ERROR:".length).trim() : message;
}

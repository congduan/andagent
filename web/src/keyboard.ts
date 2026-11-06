/**
 * 输入法遮挡兜底。
 *
 * 即便 manifest 声明了 adjustResize，Android WebView 在「沉浸式状态栏 + 部分
 * ROM」组合下也可能只压缩 visual viewport 而不缩小布局视口：此时 html/body
 * 仍是满屏高度，底部输入框就被键盘盖住。
 *
 * 这里把「被键盘吃掉的高度」量成 --kb-inset，由 .app 的高度消费（见
 * styles.css）：顶栏 / 消息区 / 输入框整列一起收缩到键盘上方。不能只给输入框
 * 加内边距——那样整列仍按满屏高排布，多出来的高度会把输入框顶出可视区，
 * 看起来反而更像"被键盘盖住"。
 *
 * 如果布局视口本身已经缩小（adjustResize 或 interactive-widget=resizes-content
 * 生效），innerHeight 会同步变小，读数归零，不会重复抬高。
 */
const ROOT = document.documentElement;

/** 小于该差值视为浏览器工具栏/地址栏抖动，不是键盘。 */
const MIN_INSET_PX = 24;
/** 让位后至少给内容区留住的布局高度：极端键盘也不把整页压没。 */
const MIN_VISIBLE_PX = 120;

let applied = -1;

/** 键盘占位 = 布局视口高度 − 可视区高度 − 可视区在文档中的偏移。 */
export function measureKeyboardInset(): number {
  const vv = window.visualViewport;
  if (!vv) return 0;
  const visible = vv.height;
  // 可视区读数为 0 时页面通常不可见，此时不该跟随。
  if (!Number.isFinite(visible) || visible <= 0) return 0;
  const gap = window.innerHeight - visible - (vv.offsetTop || 0);
  if (!Number.isFinite(gap) || gap < MIN_INSET_PX) return 0;
  const limit = Math.max(0, window.innerHeight - MIN_VISIBLE_PX);
  return Math.min(Math.round(gap), limit);
}

export function syncKeyboardInset(): void {
  const inset = measureKeyboardInset();
  if (inset === applied) return;
  applied = inset;
  if (inset > 0) ROOT.style.setProperty("--kb-inset", `${inset}px`);
  else ROOT.style.removeProperty("--kb-inset");
}

/** 在首帧渲染前安装，键盘弹出/收起时同步 `--kb-inset`。 */
export function installKeyboardInset(): void {
  const vv = window.visualViewport;
  if (!vv) return;
  vv.addEventListener("resize", syncKeyboardInset);
  vv.addEventListener("scroll", syncKeyboardInset);
  window.addEventListener("resize", syncKeyboardInset);
  window.addEventListener("orientationchange", syncKeyboardInset);
  // 收起键盘时个别 ROM 不派发 visualViewport 事件，失焦后再兜一次。
  document.addEventListener(
    "focusout",
    () => window.setTimeout(syncKeyboardInset, 120),
    true,
  );
  syncKeyboardInset();
}

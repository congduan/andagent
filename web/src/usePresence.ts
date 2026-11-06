import { useEffect, useRef, useState } from "react";

export interface Presence {
  /** 组件是否仍应渲染（关闭后还要撑过退场动画时长）。 */
  rendered: boolean;
  /** 处于「正在退场」状态：可用来挂 CSS 退场 class。 */
  closing: boolean;
}

/**
 * 覆盖层退场支持：`open` 变 false 后继续保持挂载 `exitMs`，
 * 让组件有机会播放退场动画，而不是瞬间消失。
 *
 * 仅在元素确实渲染过时才挂起定时器（初次 mount 时 open 为 false 不会空转）。
 */
export function usePresence(open: boolean, exitMs = 240): Presence {
  const [rendered, setRendered] = useState(open);
  const renderedRef = useRef(open);
  renderedRef.current = rendered;

  useEffect(() => {
    if (open) {
      setRendered(true);
      return;
    }
    if (!renderedRef.current) return;
    const timer = window.setTimeout(() => setRendered(false), exitMs);
    return () => window.clearTimeout(timer);
  }, [open, exitMs]);

  return { rendered, closing: rendered && !open };
}

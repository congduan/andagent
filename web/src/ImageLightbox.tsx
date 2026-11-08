import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useI18n } from "./i18n";

/**
 * 统一的全屏预览灯箱：滚轮 / 双指捏合缩放，拖拽平移，双击缩放切换，
 * Esc 或 × 关闭。内容通过 children 传入（`<img>`、SVG 主体等均可），
 * 缩放平移统一作用于内层包装。`zoomable=false` 时退化为纯展示
 * （用于终端输出等需要内部滚动的场景）。
 *
 * 注意：必须用 Portal 挂到 document.body。消息节点 .msg 带有
 * `animation: fade-up ... both`，动画结束后残留的 transform 会让其内部
 * 的 position: fixed 相对消息定位而非视口，导致灯箱被气泡裁剪。
 */

const MIN_SCALE = 1;
const MAX_SCALE = 8;
const DOUBLE_TAP_SCALE = 2.5;
const WHEEL_SPEED = 0.0015;
/** 单击关闭的延迟，留出双击窗口；期间触发双击则取消关闭。 */
const TAP_CLOSE_DELAY = 250;

/** 视图变换：scale 以内容中心缩放，(x, y) 为中心点位移。 */
type View = { x: number; y: number; scale: number };
type Pt = { x: number; y: number };

const IDENTITY: View = { x: 0, y: 0, scale: 1 };
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export function ImageLightbox({
  label,
  onClose,
  zoomable = true,
  children,
}: {
  /** 无障碍名称（"图片预览"、"图表预览"等）。 */
  label: string;
  onClose: () => void;
  /** false 时禁用缩放/平移手势（内容需要原生滚动时使用）。 */
  zoomable?: boolean;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const [view, setView] = useState<View>(IDENTITY);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const contentRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef(view);
  viewRef.current = view;
  const movedRef = useRef(false);
  const tapTimer = useRef(0);
  const pointers = useRef(new Map<number, Pt>());
  const dragRef = useRef<{ pt: Pt; view: View } | null>(null);
  const pinchRef = useRef<{ dist: number; center: Pt; view: View } | null>(null);

  /** 内容（img / SVG 主体）未变换时的布局尺寸，作为钳制边界的依据。 */
  const contentSize = () => {
    const stage = stageRef.current;
    const el = contentRef.current?.firstElementChild as HTMLElement | null;
    if (!stage || !el) return { w: stage?.clientWidth ?? 0, h: stage?.clientHeight ?? 0 };
    return { w: el.offsetWidth, h: el.offsetHeight };
  };

  /** 以视口点 p 为不动点缩放到 nextScale，保证所指内容点不漂移。 */
  const zoomAt = (nextScale: number, p: Pt): View => {
    const rect = stageRef.current?.getBoundingClientRect();
    const { x, y, scale } = viewRef.current;
    if (!rect) return { ...viewRef.current, scale: nextScale };
    const s = nextScale / scale;
    const cx = rect.width / 2;
    const cy = rect.height / 2;
    return {
      scale: nextScale,
      x: p.x - cx - s * (p.x - cx - x),
      y: p.y - cy - s * (p.y - cy - y),
    };
  };

  /**
   * 按内容实际尺寸钳制平移：内容大于舞台时始终铺满（不留黑边），
   * 小于舞台时保持完整可见。用舞台尺寸估算会对不满屏的小图算错
   * 边界，缩放时把光标下的点拽走——即漂移的来源。
   */
  const clampPan = (v: View): View => {
    const stage = stageRef.current;
    if (!stage) return v;
    const { w, h } = contentSize();
    const maxX = Math.abs(w * v.scale - stage.clientWidth) / 2;
    const maxY = Math.abs(h * v.scale - stage.clientHeight) / 2;
    return { ...v, x: clamp(v.x, -maxX, maxX), y: clamp(v.y, -maxY, maxY) };
  };

  /** 统一的视图更新：先钳制再同步写入 ref，连续滚轮/捏合事件间不丢步。 */
  const applyView = (v: View) => {
    const next = clampPan(v);
    viewRef.current = next;
    setView(next);
  };

  // 滚轮缩放（需非 passive 才能阻止页面滚动）。
  useEffect(() => {
    const el = stageRef.current;
    if (!el || !zoomable) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      const cur = viewRef.current;
      const next = clamp(cur.scale * Math.exp(-e.deltaY * WHEEL_SPEED), MIN_SCALE, MAX_SCALE);
      applyView(zoomAt(next, { x: e.clientX - rect.left, y: e.clientY - rect.top }));
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [zoomable]);

  // Esc 关闭。
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => () => window.clearTimeout(tapTimer.current), []);

  if (!zoomable) {
    return createPortal(
      <div className="image-lightbox" role="dialog" aria-modal="true" aria-label={label}>
        <div className="lightbox-toolbar">
          <button type="button" className="lightbox-close" onClick={onClose} aria-label={t("closePreview")}>×</button>
        </div>
        <div className="lightbox-stage lightbox-static">{children}</div>
      </div>,
      document.body,
    );
  }

  const rel = (e: { clientX: number; clientY: number }): Pt => {
    const rect = stageRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    window.clearTimeout(tapTimer.current);
    movedRef.current = false;
    pointers.current.set(e.pointerId, rel(e));
    if (pointers.current.size === 1) {
      dragRef.current = { pt: rel(e), view: viewRef.current };
      pinchRef.current = null;
    } else if (pointers.current.size === 2) {
      dragRef.current = null;
      const [a, b] = [...pointers.current.values()];
      pinchRef.current = {
        dist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
        view: viewRef.current,
      };
    }
  };

  const onPointerMove = (e: React.PointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return;
    const p = rel(e);
    pointers.current.set(e.pointerId, p);
    if (pointers.current.size >= 2 && pinchRef.current) {
      const [a, b] = [...pointers.current.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1;
      const base = pinchRef.current;
      const next = clamp(base.view.scale * (dist / base.dist), MIN_SCALE, MAX_SCALE);
      const centered = zoomAt(next, base.center);
      const dx = (a.x + b.x) / 2 - base.center.x;
      const dy = (a.y + b.y) / 2 - base.center.y;
      applyView({ ...centered, x: centered.x + dx, y: centered.y + dy });
      movedRef.current = true;
    } else if (dragRef.current) {
      const base = dragRef.current;
      if (Math.hypot(p.x - base.pt.x, p.y - base.pt.y) > 3) movedRef.current = true;
      applyView({ ...base.view, x: base.view.x + p.x - base.pt.x, y: base.view.y + p.y - base.pt.y });
    }
  };

  const onPointerUp = (e: React.PointerEvent) => {
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) pinchRef.current = null;
    const rest = [...pointers.current.values()][0];
    dragRef.current = rest ? { pt: rest, view: viewRef.current } : null;
  };

  const onStageClick = () => {
    if (movedRef.current || viewRef.current.scale > 1.01) return;
    tapTimer.current = window.setTimeout(onClose, TAP_CLOSE_DELAY);
  };

  const onDoubleClick = (e: React.MouseEvent) => {
    e.preventDefault();
    window.clearTimeout(tapTimer.current);
    if (viewRef.current.scale > 1.01) {
      applyView(IDENTITY);
    } else {
      applyView(zoomAt(DOUBLE_TAP_SCALE, rel(e)));
    }
  };

  return createPortal(
    <div className="image-lightbox" role="dialog" aria-modal="true" aria-label={label}>
      <div className="lightbox-toolbar">
        <span className="lightbox-zoom">{Math.round(view.scale * 100)}%</span>
        {view.scale > 1.01 && (
          <button
            type="button"
            className="lightbox-reset"
            title={t("resetZoom")}
            aria-label={t("resetZoom")}
            onClick={() => applyView(IDENTITY)}
          >
            1:1
          </button>
        )}
        <button type="button" className="lightbox-close" onClick={onClose} aria-label={t("closePreview")}>×</button>
      </div>
      <div
        ref={stageRef}
        className="lightbox-stage"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClick={onStageClick}
        onDoubleClick={onDoubleClick}
      >
        <div
          ref={contentRef}
          className="lightbox-content"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }}
        >
          {children}
        </div>
      </div>
    </div>,
    document.body,
  );
}

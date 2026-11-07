import { useCallback, useEffect, useRef, useState } from "react";
import { useI18n } from "./i18n";
import { createPortal } from "react-dom";
import { hasBridge, uiShare } from "./bridge";
import { Icon, type IconName } from "./icons";

/** Clipboard write with a legacy fallback for WebViews without async clipboard. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to execCommand */
  }
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}

/** 当前非空文本选区的内容（已划选时）；没有选区返回空串。 */
export function selectedText(): string {
  const selection = window.getSelection();
  if (!selection || selection.isCollapsed) return "";
  return selection.toString().trim();
}

// 全局长按撤销：消息列表可能很长，用一条 selectionchange 通知所有挂起的 useLongPress。
const pendingCancels = new Set<() => void>();
let watchingSelection = false;

function watchSelection() {
  if (watchingSelection || typeof document === "undefined") return;
  watchingSelection = true;
  document.addEventListener("selectionchange", () => {
    if (!selectedText()) return;
    pendingCancels.forEach((cancel) => cancel());
  });
}

/**
 * Long-press (or right-click) detection for message bubbles. Returns handlers
 * to spread on the message root; a short drag cancels the gesture so scrolling
 * does not pop the menu.
 *
 * Text selection wins: as soon as the browser starts a real selection (long
 * press on text / drag-select), the pending long-press is dropped and the
 * native selection handles + copy menu are left alone.
 */
export function useLongPress(onLongPress: (x: number, y: number) => void, ms = 650) {
  const timer = useRef<number | null>(null);
  const origin = useRef({ x: 0, y: 0 });

  const clear = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  useEffect(() => clear, [clear]);

  // 划选一开始就撤销待触发菜单，否则自定义菜单会盖住系统选区手柄。
  useEffect(() => {
    watchSelection();
    pendingCancels.add(clear);
    return () => {
      pendingCancels.delete(clear);
    };
  }, [clear]);

  return {
    onPointerDown: (event: React.PointerEvent) => {
      if (event.pointerType === "mouse" && event.button !== 0) return;
      // 已有选区：用户在调整选区或准备复制所选，不抢手势。
      if (selectedText()) return;
      origin.current = { x: event.clientX, y: event.clientY };
      clear();
      const { clientX, clientY } = event;
      timer.current = window.setTimeout(() => {
        timer.current = null;
        // 长按过程中已形成选区 → 交给系统选择/复制。
        if (selectedText()) return;
        onLongPress(clientX, clientY);
      }, ms);
    },
    onPointerUp: clear,
    onPointerCancel: clear,
    onPointerLeave: clear,
    onPointerMove: (event: React.PointerEvent) => {
      if (
        Math.abs(event.clientX - origin.current.x) > 10 ||
        Math.abs(event.clientY - origin.current.y) > 10
      ) {
        clear();
      }
    },
    onContextMenu: (event: React.MouseEvent) => {
      // 有选区时保留系统菜单（右键「复制」选区），不弹自定义菜单。
      if (selectedText()) return;
      event.preventDefault();
      onLongPress(event.clientX, event.clientY);
    },
  };
}

/** 菜单里的额外动作项（例如消息的「编辑」「重试」）。 */
export interface CopyMenuAction {
  icon: IconName;
  label: string;
  onClick: () => void;
  /** 置灰不可点：例如空会话禁止导出。 */
  disabled?: boolean;
}

/** 菜单顶部的只读信息行（例如 Token 用量明细），不参与点击。 */
export interface CopyMenuInfo {
  label: string;
  value: string;
}

interface CopyMenuProps {
  /** 要复制的全文；留空时菜单只显示 actions（例如会话条目的操作菜单）。 */
  text?: string;
  x: number;
  y: number;
  onClose: () => void;
  actions?: CopyMenuAction[];
  /** 只读信息区：显示在动作项之上，带分隔线。 */
  info?: CopyMenuInfo[];
  /** 信息区标题（可选）。 */
  infoTitle?: string;
}

const MENU_ITEM_HEIGHT = 33;
const MENU_PADDING = 8;

/** Context menu anchored at the long-press point: actions + 复制全文 + 分享. */
export function CopyMenu({ text = "", x, y, onClose, actions = [], info = [], infoTitle = "" }: CopyMenuProps) {
  const { t } = useI18n();
  const [state, setState] = useState<"idle" | "done" | "failed">("idle");
  const [shareFailed, setShareFailed] = useState(false);
  const [closing, setClosing] = useState(false);
  const exitRef = useRef<number | null>(null);
  const nativeBridge = hasBridge();
  const hasText = text.trim().length > 0;
  // 信息区按同样的行高估算，保证菜单贴边时不被裁掉。
  const rows =
    actions.length +
    info.length +
    (infoTitle ? 1 : 0) +
    (hasText ? 1 : 0) +
    (nativeBridge && hasText ? 1 : 0);
  const menuHeight = MENU_PADDING + rows * MENU_ITEM_HEIGHT;
  const left = Math.max(8, Math.min(x, window.innerWidth - 132));
  const top = Math.max(8, Math.min(y, window.innerHeight - menuHeight));

  // 先播退场动画，再通知父级卸载，避免菜单瞬间消失。
  const dismiss = useCallback(() => {
    if (closing) return;
    setClosing(true);
    exitRef.current = window.setTimeout(onClose, 130);
  }, [closing, onClose]);

  useEffect(() => () => {
    if (exitRef.current !== null) window.clearTimeout(exitRef.current);
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") dismiss();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [dismiss]);

  // 选字优先：已经存在选区（或长按过程中系统刚建立了选区）时直接收起菜单，
  // 把复制/分享交给系统选区菜单，避免两个弹层同时出现。
  useEffect(() => {
    if (selectedText()) {
      dismiss();
      return;
    }
    const onSelectionChange = () => {
      if (selectedText()) dismiss();
    };
    document.addEventListener("selectionchange", onSelectionChange);
    return () => document.removeEventListener("selectionchange", onSelectionChange);
  }, [dismiss]);

  const copy = async () => {
    const ok = await copyText(text);
    setState(ok ? "done" : "failed");
    window.setTimeout(dismiss, 700);
  };

  const share = () => {
    if (!uiShare(text).ok) {
      setShareFailed(true);
      window.setTimeout(() => setShareFailed(false), 1200);
      return;
    }
    dismiss();
  };

  // 动作项：先播退场动画再执行，避免气泡在菜单还挂着时突然变形。
  const runAction = (action: CopyMenuAction) => {
    if (closing || action.disabled) return;
    setClosing(true);
    exitRef.current = window.setTimeout(() => {
      exitRef.current = null;
      action.onClick();
      onClose();
    }, 130);
  };

  return createPortal(
    <div className={`copy-backdrop${closing ? " closing" : ""}`} onPointerDown={dismiss}>
      <div
        className={`copy-menu${closing ? " closing" : ""}`}
        style={{ left, top }}
        onPointerDown={(event) => event.stopPropagation()}
        role="menu"
      >
        {(info.length > 0 || infoTitle) && (
          <div className="copy-menu-info">
            {infoTitle && <div className="copy-menu-info-title">{infoTitle}</div>}
            {info.map((row) => (
              <div className="copy-menu-info-row" key={row.label}>
                <span className="copy-menu-info-label">{row.label}</span>
                <span className="copy-menu-info-value">{row.value}</span>
              </div>
            ))}
            <div className="copy-menu-sep" aria-hidden="true" />
          </div>
        )}
        {actions.map((action) => (
          <button
            type="button"
            className="copy-menu-item"
            key={action.label}
            disabled={action.disabled}
            aria-disabled={action.disabled || undefined}
            onClick={() => runAction(action)}
            role="menuitem"
          >
            <span className="copy-menu-icon" aria-hidden="true">
              <Icon name={action.icon} size={13} />
            </span>
            {action.label}
          </button>
        ))}
        {hasText && (
          <button type="button" className="copy-menu-item" onClick={copy} role="menuitem">
            <span className="copy-menu-icon" aria-hidden="true">
              <Icon name="copy" size={13} />
            </span>
            {state === "idle" ? t("copyAll") : state === "done" ? t("copied") : t("copyFailed")}
          </button>
        )}
        {nativeBridge && hasText && (
          <button type="button" className="copy-menu-item" onClick={share} role="menuitem">
            <span className="copy-menu-icon" aria-hidden="true">
              <Icon name="arrow-up-right" size={13} />
            </span>
            {shareFailed ? t("copyFailed") : t("share")}
          </button>
        )}
      </div>
    </div>,
    document.body,
  );
}

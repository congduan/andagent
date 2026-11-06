/**
 * 覆盖层（设置页/抽屉）与 WebView history 栈之间的桥。
 *
 * 每打开一层覆盖层就向 history 压一条记录，这样 Android 返回手势触发的是
 * popstate（收起覆盖层），而不是让 WebView 直接后退/退出 App。
 *
 * depth 必须是持久状态：UI 内按钮/遮罩关闭覆盖层时，sync(0) 要能把此前压入
 * 的记录逐条 back() 掉。若把栈放在 effect 局部变量里，effect 重跑会重建空
 * 栈，push 出去的历史记录就永久残留，返回键行为随之错乱。
 *
 * 归因说明：popstate 事件不携带来源，但 sync() 是自己调 back() 的发起者，
 * 因此用一个 pendingBacks 计数器记账——每次程序化 back() 加一，到达的
 * popstate 先抵扣这笔账，不再出栈、不再 closeTop。否则 UI 按钮关闭上层时
 * （例如设置列表里关掉模型设置浮层），回响会把设置层也一起关掉。
 * 用户手势的 popstate（pendingBacks===0）才是真正的出栈：depth 减一并
 * 关掉最上层覆盖层。
 */
export interface BackLayerManager {
  /** 收敛到目标层数：多了就 back()，少了就 pushState()。 */
  sync(target: number): void;
  attach(): void;
  detach(): void;
  /** 卸载时清栈（页面销毁 / StrictMode 双挂载）。 */
  destroy(): void;
  /** 当前已压入的 history 层数，供 UI 判断能否安全地 history.back()。 */
  layers(): number;
}

export function createBackLayerManager(onCloseTop: () => boolean): BackLayerManager {
  // 已压入的 history 层数；跨 effect 重跑持久，不随组件状态清零。
  let depth = 0;
  // sync() 自己发起、尚未回响的 back() 笔数。
  let pendingBacks = 0;
  let attached = false;

  const onPop = () => {
    // 自己 back() 的回响：depth 已在 sync() 里扣减，这里只销账。
    if (pendingBacks > 0) {
      pendingBacks--;
      return;
    }
    if (depth > 0) {
      depth--;
      // 用户返回手势 / 页面内「← 返回」：出栈一层并关掉最上层覆盖层。
      onCloseTop();
    }
  };

  const sync = (target: number) => {
    while (depth < target) {
      history.pushState({ layer: depth }, "");
      depth++;
    }
    while (depth > target) {
      // 立即扣减 depth 以防重复 sync 时多弹；回响由 onPop 的 pendingBacks 吸收，
      // 不再误关下层覆盖层。
      pendingBacks++;
      history.back();
      depth--;
    }
  };

  const attach = () => {
    if (attached) return;
    window.addEventListener("popstate", onPop);
    attached = true;
  };

  const detach = () => {
    if (!attached) return;
    window.removeEventListener("popstate", onPop);
    attached = false;
  };

  const destroy = () => {
    detach();
    while (depth > 0) {
      history.back();
      depth--;
    }
  };

  return { sync, attach, detach, destroy, layers: () => depth };
}

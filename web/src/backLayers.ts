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
 * 归因说明（R63 review 结论）：popstate 事件不携带来源，无法区分"用户返回"
 * 和"我们自己的 back()"。因此不做来源归因，而是让两类事件走同一条收敛路径：
 * 有层（depth>0）就出栈一层并尝试关覆盖层，没层就跳过。程序化 back() 的
 * popstate 到达时 depth 已被 sync() 同步扣减，自然是 no-op；用户手势事件则
 * 正常关层。两种事件交错到达也不会双重出栈或误关下层（极端交错下最坏情形
 * 是刚打开的覆盖层被多关一次，状态仍一致，见单测 4）。
 */
export interface BackLayerManager {
  /** 收敛到目标层数：多了就 back()，少了就 pushState()。 */
  sync(target: number): void;
  attach(): void;
  detach(): void;
  /** 卸载时清栈（页面销毁 / StrictMode 双挂载）。 */
  destroy(): void;
}

export function createBackLayerManager(onCloseTop: () => boolean): BackLayerManager {
  // 已压入的 history 层数；跨 effect 重跑持久，不随组件状态清零。
  let depth = 0;
  let attached = false;

  const onPop = () => {
    if (depth > 0) {
      depth--;
      // 关闭最上层覆盖层；若 UI 状态已先行关闭（例如按钮关闭后程序化
      // back() 的回响到达），closeTop 返回 false，自然跳过，不会误关下层。
      onCloseTop();
    }
    // depth === 0：程序化 back() 的回响，或外部导航——交由浏览器默认行为。
  };

  const sync = (target: number) => {
    while (depth < target) {
      history.pushState({ layer: depth }, "");
      depth++;
    }
    while (depth > target) {
      history.back();
      depth--; // back 异步，立即扣减以防重复调用 sync 时多弹；其回响由 onPop 的 depth===0 分支吸收。
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

  return { sync, attach, detach, destroy };
}

import { afterEach, describe, expect, it, vi } from "vitest";
import { createBackLayerManager } from "./backLayers";

function setup(onCloseResult = true) {
  const push = vi.spyOn(window.history, "pushState").mockImplementation(() => {});
  const back = vi.spyOn(window.history, "back").mockImplementation(() => {});
  const onCloseTop = vi.fn(() => onCloseResult);
  const mgr = createBackLayerManager(onCloseTop);
  return { push, back, onCloseTop, mgr };
}

function userBack() {
  window.dispatchEvent(new PopStateEvent("popstate"));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("createBackLayerManager", () => {
  it("sync(1) 压一条，sync(0) 回收一条", () => {
    const { push, back, mgr } = setup();
    mgr.attach();
    mgr.sync(1);
    expect(push).toHaveBeenCalledTimes(1);
    expect(back).toHaveBeenCalledTimes(0);

    mgr.sync(0);
    expect(back).toHaveBeenCalledTimes(1);
    mgr.detach();
  });

  it("缺陷回归：UI 关闭覆盖层必须 back() 恰好一次", () => {
    const { push, back, mgr } = setup();
    mgr.attach();
    mgr.sync(1);
    // 模拟点击按钮/遮罩：state 直接归零，effect 重跑只做 sync(0)。
    mgr.sync(0);
    expect(push).toHaveBeenCalledTimes(1);
    expect(back).toHaveBeenCalledTimes(1);
    // 再跑一次 sync(0) 不应重复回收（防止多弹一层历史）。
    mgr.sync(0);
    expect(back).toHaveBeenCalledTimes(1);
    mgr.detach();
  });

  it("开关 5 次不累积 history 记录", () => {
    const { push, back, mgr } = setup();
    mgr.attach();
    for (let i = 0; i < 5; i++) {
      mgr.sync(1);
      mgr.sync(0);
    }
    expect(push).toHaveBeenCalledTimes(5);
    expect(back).toHaveBeenCalledTimes(5);
    mgr.detach();
  });

  it("用户返回手势关闭最上层，随后 sync(0) 不再多弹", () => {
    const { push, back, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(1);
    userBack();
    expect(onCloseTop).toHaveBeenCalledTimes(1);
    mgr.sync(0);
    expect(back).toHaveBeenCalledTimes(0);
    expect(push).toHaveBeenCalledTimes(1);
    mgr.detach();
  });

  it("两层：压 2 条，两次返回各关一层", () => {
    const { push, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(2);
    expect(push).toHaveBeenCalledTimes(2);
    userBack();
    userBack();
    expect(onCloseTop).toHaveBeenCalledTimes(2);
    mgr.detach();
  });

  it("destroy 清空已压层并摘掉监听", () => {
    const { back, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(2);
    const remove = vi.spyOn(window, "removeEventListener");
    mgr.destroy();
    expect(back).toHaveBeenCalledTimes(2);
    expect(remove).toHaveBeenCalledWith("popstate", expect.any(Function));
    userBack();
    expect(onCloseTop).toHaveBeenCalledTimes(0);
  });

  it("程序化 back() 的回响不再二次出栈，depth 与 sync 收敛一致", () => {
    const { push, back, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(2);
    // UI 关闭抽屉（2→1）：sync 调一次 back，其 popstate 稍后才到。
    mgr.sync(1);
    expect(back).toHaveBeenCalledTimes(1);
    userBack(); // 该 back 的回响到达（无归因，depth 1→0 并尝试关层一次）。
    expect(onCloseTop).toHaveBeenCalledTimes(1);
    // depth 已为 0：sync(0) 无需再 back，sync(1) 需要一条新 push。
    mgr.sync(0);
    expect(back).toHaveBeenCalledTimes(1);
    mgr.sync(1);
    expect(push).toHaveBeenCalledTimes(3);
    mgr.detach();
  });

  it("用户返回与 UI 关闭交错不双重出栈", () => {
    const { back, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(2);
    userBack(); // 用户返回关一层（depth 2→1，close×1）。
    mgr.sync(0); // UI 关闭另一层：depth 1→0，back×1。
    expect(onCloseTop).toHaveBeenCalledTimes(1);
    expect(back).toHaveBeenCalledTimes(1);
    userBack(); // sync(0) 那次 back 的回响到达：depth===0，不再出栈。
    expect(onCloseTop).toHaveBeenCalledTimes(1);
    mgr.detach();
  });

  it("回归：设置子页返回只回设置列表，不连带关闭设置", () => {
    const state = { settings: true, subpage: true };
    const mgr = createBackLayerManager(() => {
      if (state.subpage) { state.subpage = false; return true; }
      if (state.settings) { state.settings = false; return true; }
      return false;
    });
    const push = vi.spyOn(window.history, "pushState").mockImplementation(() => {});
    mgr.attach();
    mgr.sync(2); // 设置层 + 子页层
    expect(push).toHaveBeenCalledTimes(2);
    expect(mgr.layers()).toBe(2);

    userBack();
    expect(state).toEqual({ settings: true, subpage: false });
    mgr.sync(1); // effect 收敛：层级已对齐，不应再 back()
    expect(mgr.layers()).toBe(1);

    userBack();
    expect(state).toEqual({ settings: false, subpage: false });
    mgr.detach();
  });

  it("重复 attach 只挂一个监听", () => {
    const { onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.attach();
    mgr.sync(1);
    userBack();
    expect(onCloseTop).toHaveBeenCalledTimes(1);
    mgr.detach();
  });
});

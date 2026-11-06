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

  it("自己 back() 引发的 popstate 不重复出栈、不误关下层", () => {
    const { push, back, onCloseTop, mgr } = setup();
    mgr.attach();
    mgr.sync(2);
    // UI 关闭抽屉（2→1）：sync 调一次 back，其 popstate 稍后才到。
    mgr.sync(1);
    expect(back).toHaveBeenCalledTimes(1);
    userBack();
    expect(onCloseTop).toHaveBeenCalledTimes(0);
    // depth 仍为 1：再 sync(0) 需要一次 back，而不是 0 次或 2 次。
    mgr.sync(0);
    expect(back).toHaveBeenCalledTimes(2);
    mgr.sync(1);
    expect(push).toHaveBeenCalledTimes(3);
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

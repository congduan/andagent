import { beforeEach, describe, expect, it } from "vitest";
import { installKeyboardInset, measureKeyboardInset, syncKeyboardInset } from "./keyboard";

/** 模拟 WebView 几何：layout = 布局视口高度，visible = 可视区高度。 */
function stubViewport(layout: number, visible: number, offsetTop = 0): void {
  Object.defineProperty(window, "innerHeight", { configurable: true, value: layout });
  Object.defineProperty(window, "visualViewport", {
    configurable: true,
    value: {
      height: visible,
      offsetTop,
      addEventListener: () => {},
      removeEventListener: () => {},
    },
  });
}

beforeEach(() => {
  document.documentElement.style.removeProperty("--kb-inset");
});

describe("measureKeyboardInset", () => {
  it("布局视口没被顶起时，按可视区差值让位", () => {
    stubViewport(800, 500);
    expect(measureKeyboardInset()).toBe(300);
  });

  it("布局视口已经缩小（adjustResize 生效）时不重复让位", () => {
    stubViewport(500, 500);
    expect(measureKeyboardInset()).toBe(0);
  });

  it("把可视区在文档中的偏移算进差值", () => {
    stubViewport(800, 500, 20);
    expect(measureKeyboardInset()).toBe(280);
  });

  it("忽略工具栏抖动级别的读数", () => {
    stubViewport(800, 790);
    expect(measureKeyboardInset()).toBe(0);
  });

  it("可视区读数为 0（页面不可见）时不跟随", () => {
    stubViewport(800, 0);
    expect(measureKeyboardInset()).toBe(0);
  });

  it("极端键盘高度也不把整页压没", () => {
    stubViewport(800, 50);
    expect(measureKeyboardInset()).toBe(680);
  });
});

describe("installKeyboardInset", () => {
  it("弹出时写 --kb-inset，收起后清掉", () => {
    stubViewport(800, 500);
    installKeyboardInset();
    expect(document.documentElement.style.getPropertyValue("--kb-inset")).toBe("300px");

    stubViewport(800, 800);
    syncKeyboardInset();
    expect(document.documentElement.style.getPropertyValue("--kb-inset")).toBe("");
  });
});

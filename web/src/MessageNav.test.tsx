import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MessageNav, type MessageNavItem } from "./MessageNav";
import { setLanguageMode } from "./i18n";

const ITEMS: MessageNavItem[] = [
  { id: "u-0-1", text: "第一句话" },
  { id: "u-2-2", text: "第二句话\n带换行" },
];

beforeEach(() => {
  setLanguageMode("zh");
  // jsdom 没有实现平滑滚动，逐个打桩以便断言跳转目标。
  Element.prototype.scrollIntoView = vi.fn();
  document.body.innerHTML = '<div class="viewport"><div id="msg-u-0-1"></div><div id="msg-u-2-2"></div></div>';
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("MessageNav", () => {
  test("收起时只显示入口和条数", () => {
    render(<MessageNav items={ITEMS} />);
    expect(screen.getByRole("button", { name: "快速查看输入过的每条消息" })).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.getByText("2")).toBeTruthy();
  });

  test("展开后按顺序列出每一条输入", () => {
    render(<MessageNav items={ITEMS} />);
    fireEvent.click(screen.getByRole("button", { name: "快速查看输入过的每条消息" }));
    const panel = within(screen.getByRole("dialog", { name: "对话记录" }));
    expect(panel.getByText("第一句话")).toBeTruthy();
    // 换行折成空格，避免列表项被撑成多段。
    expect(panel.getByText("第二句话 带换行")).toBeTruthy();
    expect(panel.getByText("1")).toBeTruthy();
    expect(panel.getByText("2")).toBeTruthy();
    expect(panel.getByText("2 条")).toBeTruthy();
  });

  test("点条目滚到对应消息并收起面板", () => {
    render(<MessageNav items={ITEMS} />);
    fireEvent.click(screen.getByRole("button", { name: "快速查看输入过的每条消息" }));
    fireEvent.click(screen.getByRole("button", { name: "跳到第 2 条消息" }));
    const target = document.getElementById("msg-u-2-2")!;
    expect(target.scrollIntoView).toHaveBeenCalled();
    expect(target.classList.contains("msg-flash")).toBe(true);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  test("Esc 收起面板", () => {
    render(<MessageNav items={ITEMS} />);
    fireEvent.click(screen.getByRole("button", { name: "快速查看输入过的每条消息" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  test("没有输入过消息时不渲染入口", () => {
    render(<MessageNav items={[]} />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

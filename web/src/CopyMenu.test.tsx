import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { selectedText, useLongPress } from "./CopyMenu";

/** 用固定选区替换 window.getSelection，模拟“已划选 / 未划选”。 */
function mockSelection(text: string) {
  vi.spyOn(window, "getSelection").mockReturnValue({
    isCollapsed: text.length === 0,
    toString: () => text,
  } as unknown as Selection);
}

function Harness({ onLongPress }: { onLongPress: (x: number, y: number) => void }) {
  const handlers = useLongPress(onLongPress, 10);
  return (
    <div data-testid="msg" {...handlers}>
      hello world
    </div>
  );
}

describe("selectedText", () => {
  afterEach(() => vi.restoreAllMocks());

  test("无选区时返回空串，有选区时返回选中文本", () => {
    mockSelection("");
    expect(selectedText()).toBe("");
    mockSelection("hello");
    expect(selectedText()).toBe("hello");
  });
});

describe("useLongPress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  test("没有选区时长按弹出自定义菜单", () => {
    mockSelection("");
    const onLongPress = vi.fn();
    render(<Harness onLongPress={onLongPress} />);
    fireEvent.pointerDown(screen.getByTestId("msg"), { clientX: 12, clientY: 24 });
    act(() => {
      vi.advanceTimersByTime(10);
    });
    expect(onLongPress).toHaveBeenCalledWith(12, 24);
  });

  test("已有选区时不抢手势（交给系统划选/复制）", () => {
    mockSelection("hello");
    const onLongPress = vi.fn();
    render(<Harness onLongPress={onLongPress} />);
    fireEvent.pointerDown(screen.getByTestId("msg"), { clientX: 12, clientY: 24 });
    act(() => {
      vi.advanceTimersByTime(10);
    });
    expect(onLongPress).not.toHaveBeenCalled();
  });

  test("长按过程中系统开始划选则撤销菜单", () => {
    const onLongPress = vi.fn();
    const getSelection = vi
      .spyOn(window, "getSelection")
      .mockReturnValue({ isCollapsed: true, toString: () => "" } as unknown as Selection);
    render(<Harness onLongPress={onLongPress} />);
    fireEvent.pointerDown(screen.getByTestId("msg"), { clientX: 12, clientY: 24 });
    getSelection.mockReturnValue({
      isCollapsed: false,
      toString: () => "ell",
    } as unknown as Selection);
    act(() => {
      document.dispatchEvent(new Event("selectionchange"));
    });
    act(() => {
      vi.advanceTimersByTime(10);
    });
    expect(onLongPress).not.toHaveBeenCalled();
  });

  test("有选区时右键保留系统菜单，无选区时接管右键", () => {
    mockSelection("hello");
    const onLongPress = vi.fn();
    render(<Harness onLongPress={onLongPress} />);
    const node = screen.getByTestId("msg");
    const selected = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
    node.dispatchEvent(selected);
    expect(selected.defaultPrevented).toBe(false);
    expect(onLongPress).not.toHaveBeenCalled();

    mockSelection("");
    const empty = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
    node.dispatchEvent(empty);
    expect(empty.defaultPrevented).toBe(true);
    expect(onLongPress).toHaveBeenCalledTimes(1);
  });
});

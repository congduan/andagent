import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { appConfirm, appRichConfirm, cancelConfirmDialog, ConfirmDialog } from "./ConfirmDialog";

// R106 必修项1: spec 只含 CARD + BUTTON_GROUP；完整参数由宿主自渲染，不再进 spec。
const DECISION_SPEC = JSON.stringify({
  title: "危险操作确认",
  fields: [
    { type: "CARD", title: "危险操作确认", text: "删除文件 /sdcard/x" },
    {
      type: "BUTTON_GROUP",
      key: "decision",
      required: true,
      buttons: [
        { value: "cancel", label: "取消" },
        { value: "confirm", label: "允许执行" },
      ],
    },
  ],
});
const ARGS_TEXT = '{"tool":"file_write","arguments":{"path":"/sdcard/x"}}';
const ARGS_LABEL = "完整参数";

/** 触发 appRichConfirm 并同步 flush React 状态，返回尚未 resolve 的 promise。 */
function openRich(
  specText: string = DECISION_SPEC,
  argsText: string = ARGS_TEXT,
  argsLabel: string = ARGS_LABEL,
): Promise<Record<string, unknown> | null> {
  let promise!: Promise<Record<string, unknown> | null>;
  act(() => {
    promise = appRichConfirm(specText, argsText, argsLabel);
  });
  return promise;
}

beforeEach(() => {
  localStorage.setItem("andagent.language", "zh");
});

afterEach(() => {
  cleanup();
});

describe("ConfirmDialog · R106 rich confirm", () => {
  it("renders the a2ui decision card and the host-rendered full arguments block", () => {
    render(<ConfirmDialog />);
    openRich();
    expect(document.querySelector(".confirm-overlay")).toBeTruthy();
    expect(document.querySelector(".confirm-card-rich")).toBeTruthy();
    expect(screen.getByText("删除文件 /sdcard/x")).toBeTruthy();
    expect(screen.getByText("允许执行")).toBeTruthy();
    expect(document.querySelector(".a2ui-button-group")).toBeTruthy();
    // R106 必修项1: argsLabel 作小标题，argsText 全文由宿主用 <pre> 渲染，不 clip。
    expect(document.querySelector(".confirm-args-label")!.textContent).toBe(ARGS_LABEL);
    const args = document.querySelector("pre.confirm-args");
    expect(args).toBeTruthy();
    expect(args!.textContent).toBe(ARGS_TEXT);
  });

  it("submitting the a2ui card resolves with the decision payload", async () => {
    render(<ConfirmDialog />);
    const promise = openRich();
    fireEvent.click(screen.getByText("允许执行"));
    fireEvent.click(screen.getByText("提交"));
    await expect(promise).resolves.toMatchObject({ decision: "confirm" });
  });

  it("clicking the overlay (cancel) resolves null", async () => {
    render(<ConfirmDialog />);
    const promise = openRich();
    fireEvent.click(document.querySelector(".confirm-overlay")!);
    await expect(promise).resolves.toBeNull();
  });

  it("cancelConfirmDialog() (stop) resolves the rich request with null", async () => {
    render(<ConfirmDialog />);
    const promise = openRich();
    let cancelled = false;
    act(() => {
      cancelled = cancelConfirmDialog();
    });
    expect(cancelled).toBe(true);
    await expect(promise).resolves.toBeNull();
  });

  it("a new rich request takes over and remounts the card with a cleared selection", async () => {
    render(<ConfirmDialog />);
    const first = openRich();
    // 先在第一张卡上选中「允许执行」——A2uiCard 给选中按钮加 a2ui-option-btn-active。
    fireEvent.click(screen.getByText("允许执行"));
    expect(document.querySelector(".a2ui-option-btn-active")).toBeTruthy();
    const second = openRich();
    await expect(first).resolves.toBeNull();
    // 新请求用新 id 作 key 强制重挂载：内嵌 A2uiCard 是全新 state，选中态清空。
    expect(document.querySelector(".a2ui-option-btn-active")).toBeNull();
    // 重挂载后重新选中并提交，第二个请求仍能正常结算。
    fireEvent.click(screen.getByText("允许执行"));
    fireEvent.click(screen.getByText("提交"));
    await expect(second).resolves.toMatchObject({ decision: "confirm" });
  });

  it("plain-text appConfirm still resolves true as before", async () => {
    render(<ConfirmDialog />);
    let promise!: Promise<boolean>;
    act(() => {
      promise = appConfirm("确定删除？");
    });
    expect(screen.getByText("确定删除？")).toBeTruthy();
    fireEvent.click(screen.getByText("确定"));
    await expect(promise).resolves.toBe(true);
  });
});

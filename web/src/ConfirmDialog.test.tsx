import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { appConfirm, appRichConfirm, cancelConfirmDialog, ConfirmDialog } from "./ConfirmDialog";

// R142 交互重构后：spec 只含风险摘要 CARD；确认/取消按钮由宿主底部渲染（单步点击），
// 完整参数由宿主自渲染，不再进 spec（也不再有 BUTTON_GROUP）。
const DECISION_SPEC = JSON.stringify({
  title: "危险操作确认",
  fields: [
    { type: "CARD", title: "危险操作确认", text: "删除文件 /sdcard/x" },
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
    // R142：宿主底部单步确认按钮（复用 confirmOk 文案「确定」/「OK」）。
    expect(screen.getByText("确定")).toBeTruthy();
    // R106 必修项1: argsLabel 作小标题，argsText 全文由宿主用 <pre> 渲染，不 clip。
    expect(document.querySelector(".confirm-args-label")!.textContent).toBe(ARGS_LABEL);
    const args = document.querySelector("pre.confirm-args");
    expect(args).toBeTruthy();
    expect(args!.textContent).toBe(ARGS_TEXT);
  });

  it("submitting the a2ui card resolves with the decision payload", async () => {
    // R142 交互重构后：确认/取消是宿主底部单步按钮（允许执行=confirmOk），点击即结算。
    render(<ConfirmDialog />);
    const promise = openRich();
    fireEvent.click(screen.getByText("确定"));
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
    // R142 后宿主按钮单步结算； takeover 断言保持「新卡全新 state、旧 promise 结 null」。
    render(<ConfirmDialog />);
    const first = openRich();
    const second = openRich();
    await expect(first).resolves.toBeNull();
    expect(document.querySelector(".confirm-card-rich")).toBeTruthy();
    // 重挂载后点击宿主确认按钮，第二个请求仍能正常结算。
    fireEvent.click(screen.getByText("确定"));
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

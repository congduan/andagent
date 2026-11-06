import { StrictMode } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { DEFAULT_SETTINGS, persistSettings } from "./bridge";
import { needsModelSetup } from "./modelConfig";
import { setLanguageMode } from "./i18n";

vi.mock("./Markdown", () => ({ MarkdownText: () => null }));
vi.mock("./Settings", () => ({ SettingsPanel: () => null }));
beforeEach(() => {
  setLanguageMode("zh");
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
});
afterEach(async () => {
  cleanup();
  // Let the browser's queued history.back events finish before another mount.
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 30)); });
  localStorage.clear(); vi.unstubAllGlobals();
});

describe("startup model setup", () => {
  it("survives StrictMode mount cleanup and remembers an explicit skip", async () => {
    render(<StrictMode><App /></StrictMode>);
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 30)); });
    expect(screen.getByRole("dialog", { name: "连接大模型" })).toBeTruthy();
    expect(needsModelSetup(DEFAULT_SETTINGS)).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "稍后" }));
    // 关闭后先播退场动画再卸载，这里等它真正消失。
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(needsModelSetup(DEFAULT_SETTINGS)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "去配置" }));
    expect(screen.getByRole("dialog", { name: "模型配置" })).toBeTruthy();
  });

  it("skips onboarding for an existing keyless local configuration", () => {
    persistSettings({ ...DEFAULT_SETTINGS, baseUrl: "http://lan:11434/v1", model: "local-model" });
    render(<StrictMode><App /></StrictMode>);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByText("还没有连接大模型。")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "local-model" }));
    expect(screen.getByRole("dialog", { name: "模型配置" })).toBeTruthy();
  });
});

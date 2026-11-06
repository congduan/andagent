import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_SETTINGS, loadSettings } from "./bridge";
import { setLanguageMode } from "./i18n";
import { ModelSetup } from "./ModelSetup";

beforeEach(() => setLanguageMode("zh"));
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); });
const configured = { ...DEFAULT_SETTINGS, baseUrl: "https://models.example/v1", model: "agent-model", apiKey: "test-key" };

describe("model setup flow", () => {
  it("lets the user skip first-run setup without saving credentials", () => {
    const close = vi.fn(); const save = vi.fn();
    render(<ModelSetup settings={DEFAULT_SETTINGS} onboarding onClose={close} onSave={save} />);
    fireEvent.click(screen.getByRole("button", { name: "稍后" }));
    expect(close).toHaveBeenCalled(); expect(save).not.toHaveBeenCalled();
    expect(loadSettings().model).toBe("");
  });

  it("clears credentials when selecting a different provider and saves keyless local models", () => {
    const save = vi.fn();
    render(<ModelSetup settings={configured} onClose={vi.fn()} onSave={save} />);
    fireEvent.change(screen.getByLabelText("模型服务商"), { target: { value: "ollama" } });
    expect((screen.getByLabelText("API Key（可选）") as HTMLInputElement).value).toBe("");
    fireEvent.change(screen.getByLabelText("模型 ID"), { target: { value: "local-model" } });
    fireEvent.click(screen.getByRole("button", { name: "不测试，直接保存" }));
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ model: "local-model", apiKey: "", baseUrl: "http://localhost:11434/v1" }));
    expect(loadSettings().model).toBe("local-model");
  });

  it("keeps failed tests open, and supports manually saving after a listing failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 404 })));
    const save = vi.fn(); const close = vi.fn();
    render(<ModelSetup settings={{ ...configured, model: "" }} onClose={close} onSave={save} />);
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));
    await screen.findByRole("alert");
    expect(save).not.toHaveBeenCalled(); expect(close).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("模型 ID"), { target: { value: "manual-model" } });
    fireEvent.click(screen.getByRole("button", { name: "不测试，直接保存" }));
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ model: "manual-model" }));
  });

  it("fetches a model then verifies tool calling before saving", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: [{ id: "listed-model" }] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ function: { name: "ping" } }] } }] })));
    vi.stubGlobal("fetch", fetcher);
    const save = vi.fn();
    render(<ModelSetup settings={{ ...configured, model: "" }} onClose={vi.fn()} onSave={save} />);
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ model: "listed-model" })));
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("aborts an in-flight test when the panel closes", async () => {
    let signal: AbortSignal | undefined;
    vi.stubGlobal("fetch", vi.fn((_url, init) => { signal = init.signal; return new Promise(() => {}); }));
    const view = render(<ModelSetup settings={configured} onClose={vi.fn()} onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));
    await act(async () => view.unmount());
    expect(signal?.aborted).toBe(true);
  });
});

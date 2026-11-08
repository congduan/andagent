import { beforeEach, describe, expect, it } from "vitest";
import { AgentEngine } from "./agent";
import { DEFAULT_SETTINGS } from "./bridge";

// R105: the cron a2ui consumption loop — inbox -> consumeA2uiForms() ->
// appendA2uiForm() -> assistant message with the ```a2ui fence. Submitting the
// card later returns via composer-fill (tested in A2uiCard.test.tsx).

const FENCE = '```a2ui\n{"title":"晨报","fields":[{"type":"SWITCH","key":"todo","prefill":true}]}\n```';

type UiMsg = {
  role: string;
  content: Array<{ type: string; text?: string }>;
  metadata?: { custom?: { usage?: { input: number; output: number } } };
};

const messagesOf = (engine: AgentEngine): UiMsg[] =>
  engine.getSnapshot().messages as unknown as UiMsg[];

beforeEach(() => {
  localStorage.clear();
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
});

describe("AgentEngine · cron a2ui form injection", () => {
  it("injects a deterministic assistant message (no LLM round-trip)", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    engine.appendA2uiForm(FENCE);

    const msgs = messagesOf(engine);
    expect(msgs).toHaveLength(1);
    expect(msgs[0].role).toBe("assistant");
    // toUiMessages keeps a plain text part verbatim; the fence renders via Markdown -> A2uiCard.
    expect(msgs[0].content).toEqual([{ type: "text", text: FENCE }]);
    // ZERO_USAGE: a cron form must never be billed as model tokens.
    expect(msgs[0].metadata?.custom?.usage).toEqual({ input: 0, output: 0 });
  });

  it("ignores a blank fence", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    engine.appendA2uiForm("   \n  ");
    expect(messagesOf(engine)).toHaveLength(0);
  });

  it("isRunning is false when idle and guards injection during a running turn", () => {
    const engine = new AgentEngine({ ...DEFAULT_SETTINGS });
    expect(engine.isRunning).toBe(false);
    // App.tsx gates the durable inbox drain on this getter; as a defense in
    // depth appendA2uiForm itself still refuses to splice into a running turn.
    (engine as unknown as { running: boolean }).running = true;
    expect(engine.isRunning).toBe(true);
    engine.appendA2uiForm(FENCE);
    expect(messagesOf(engine)).toHaveLength(0);
  });
});

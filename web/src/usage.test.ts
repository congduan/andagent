import { describe, it, expect } from "vitest";
import { addUsage, emptyUsage, fmtTokens, sumMessageUsage, sumUsageBreakdown } from "./usage";

// R40: token 消耗格式化与会话级累加纯逻辑。

describe("fmtTokens", () => {
  it("renders small counts as-is", () => {
    expect(fmtTokens(0)).toBe("0");
    expect(fmtTokens(1)).toBe("1");
    expect(fmtTokens(842)).toBe("842");
    expect(fmtTokens(999)).toBe("999");
  });

  it("abbreviates >=1000 with one decimal k", () => {
    expect(fmtTokens(1000)).toBe("1.0k");
    expect(fmtTokens(2100)).toBe("2.1k");
    expect(fmtTokens(45600)).toBe("45.6k");
    expect(fmtTokens(1234567)).toBe("1234.6k");
  });

  it("treats invalid input as zero", () => {
    expect(fmtTokens(-5)).toBe("0");
    expect(fmtTokens(NaN)).toBe("0");
  });
});

describe("addUsage", () => {
  it("accumulates into the accumulator", () => {
    const acc = emptyUsage();
    addUsage(acc, { input: 100, output: 20, totalTokens: 120 });
    addUsage(acc, { input: 30, output: 5, totalTokens: 35 });
    expect(acc).toEqual({ input: 130, output: 25, totalTokens: 155 });
  });

  it("tolerates missing or null usage", () => {
    const acc = emptyUsage();
    addUsage(acc, null);
    addUsage(acc, undefined);
    addUsage(acc, {});
    expect(acc).toEqual({ input: 0, output: 0, totalTokens: 0 });
  });
});

describe("sumMessageUsage", () => {
  const usage = (input: number, output: number) => ({
    input,
    output,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: input + output,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  });

  it("sums assistant messages only", () => {
    const messages = [
      { role: "user", content: "hi", timestamp: 1 },
      { role: "assistant", usage: usage(100, 10), stopReason: "stop", timestamp: 2 },
      { role: "toolResult", toolCallId: "t", content: [], isError: false, timestamp: 3 },
      { role: "assistant", usage: usage(200, 20), stopReason: "toolUse", timestamp: 4 },
    ];
    expect(sumMessageUsage(messages)).toEqual({ input: 300, output: 30, totalTokens: 330 });
  });

  it("skips aborted messages (usage not reported)", () => {
    const messages = [
      { role: "assistant", usage: usage(100, 10), stopReason: "stop", timestamp: 1 },
      { role: "assistant", usage: usage(999, 999), stopReason: "aborted", timestamp: 2 },
    ];
    expect(sumMessageUsage(messages)).toEqual({ input: 100, output: 10, totalTokens: 110 });
  });

  it("returns zeros for empty or malformed input", () => {
    expect(sumMessageUsage([])).toEqual({ input: 0, output: 0, totalTokens: 0 });
    expect(sumMessageUsage([null, { role: "assistant" }])).toEqual({
      input: 0,
      output: 0,
      totalTokens: 0,
    });
  });
});

describe("sumUsageBreakdown", () => {
  it("also accumulates cache read/write and counts turns", () => {
    const messages = [
      { role: "user", content: "hi" },
      { role: "assistant", usage: { input: 100, output: 10, cacheRead: 400, cacheWrite: 20, totalTokens: 530 } },
      { role: "assistant", usage: { input: 50, output: 5 }, stopReason: "toolUse" },
      { role: "assistant", usage: { input: 999, output: 999 }, stopReason: "aborted" },
    ];
    expect(sumUsageBreakdown(messages)).toEqual({
      input: 150,
      output: 15,
      cacheRead: 400,
      cacheWrite: 20,
      totalTokens: 530,
      turns: 2,
    });
  });

  it("is all zeros for an empty conversation", () => {
    expect(sumUsageBreakdown([])).toEqual({
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      totalTokens: 0,
      turns: 0,
    });
  });
});

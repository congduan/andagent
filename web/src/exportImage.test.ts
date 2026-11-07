import { beforeEach, describe, expect, it } from "vitest";
import {
  LONG_IMAGE_MAX_HEIGHT,
  LONG_IMAGE_WIDTH,
  conversationSections,
  layoutLongImage,
  markdownToBlocks,
  pixelRatio,
  wrapText,
  type Measure,
} from "./exportImage";
import { SessionStore } from "./sessions";

/** 估算测量：CJK 记 1 个字号宽，ASCII 记 0.55 个字号宽。 */
const measure: Measure = (text, style) => {
  const size = Number(/(\d+(?:\.\d+)?)px/.exec(style)?.[1] ?? 15);
  let width = 0;
  for (const ch of text) width += (/[\u3000-\u9fff]/.test(ch) ? size : size * 0.55);
  return width;
};

beforeEach(() => {
  localStorage.clear();
  delete window.AndroidBridge;
});

describe("markdownToBlocks", () => {
  it("keeps prose, drops inline markers", () => {
    const blocks = markdownToBlocks("# 标题\n\n**粗体** 和 `code` 与 [链接](https://a.b)\n\n- 列表项");
    expect(blocks).toEqual([
      { kind: "text", text: "标题" },
      { kind: "text", text: "粗体 和 code 与 链接" },
      { kind: "text", text: "· 列表项" },
    ]);
  });

  it("降级 ECharts/mermaid 围栏为占位框，普通代码保留", () => {
    const blocks = markdownToBlocks("```chart\n{\"series\":[]}\n```\n\n```js\nconst a = 1;\n```");
    expect(blocks[0]).toEqual({ kind: "placeholder", label: "CHART" });
    expect(blocks[1]).toEqual({ kind: "code", lang: "js", text: "const a = 1;" });
  });
});

describe("wrapText", () => {
  it("breaks long CJK text into lines under the width", () => {
    const style = "400 15px sans-serif";
    const lines = wrapText((text) => measure(text, style), "这是一段很长很长的中文内容需要折行", 60);
    expect(lines.length).toBeGreaterThan(1);
    for (const line of lines) expect(measure(line, style)).toBeLessThanOrEqual(60);
  });
});

describe("conversationSections", () => {
  it("collects thinking, tool placeholder and result summary", () => {
    SessionStore.save("s_long", [
      { role: "user", content: "帮我看看电量", timestamp: 1_700_000_000_000 },
      {
        role: "assistant",
        timestamp: 1_700_000_001_000,
        content: [
          { type: "thinking", thinking: "先读取电量" },
          { type: "toolCall", id: "c1", name: "battery_detail", arguments: {} },
          { type: "text", text: "电量 80%" },
        ],
      },
      { role: "toolResult", toolCallId: "c1", content: [{ type: "text", text: "level=80\nscale=100" }] },
      { role: "assistant", content: [{ type: "text", text: "```chart\n{}\n```" }] },
    ]);

    const sections = conversationSections("s_long");
    expect(sections).toHaveLength(3);
    expect(sections[0].role).toBe("user");
    expect(sections[1].blocks[0].kind).toBe("thinking");
    expect(sections[1].blocks[1]).toEqual({
      kind: "tool",
      name: "battery_detail",
      summary: "level=80 scale=100",
    });
    expect(sections[2].blocks[0]).toEqual({ kind: "placeholder", label: "CHART" });
  });
});

describe("layoutLongImage", () => {
  it("固定 720px 宽，高度随内容增长，图表正文不进长图", () => {
    const shortLayout = layoutLongImage(
      [{ role: "user", at: 0, blocks: [{ kind: "text", text: "hi" }] }],
      { title: "会话标题", generatedAt: 0, measure, dark: false },
    );
    const longLayout = layoutLongImage(
      [
        { role: "user", at: 0, blocks: [{ kind: "text", text: "hi" }] },
        {
          role: "assistant",
          at: 0,
          blocks: [
            { kind: "text", text: "第一段\n第二段" },
            { kind: "code", lang: "js", text: "const a = 1;" },
            { kind: "placeholder", label: "MERMAID" },
          ],
        },
      ],
      { title: "会话标题", generatedAt: 0, measure, dark: false },
    );

    expect(shortLayout.width).toBe(LONG_IMAGE_WIDTH);
    expect(longLayout.height).toBeGreaterThan(shortLayout.height);

    const texts = longLayout.ops.flatMap((op) => (op.op === "text" ? [op.text] : []));
    expect(texts).toContain("会话标题");
    expect(texts.some((text) => text.includes("［MERMAID］"))).toBe(true);
    expect(texts.some((text) => text.includes("const a = 1;"))).toBe(true);
    expect(texts.some((text) => text.includes("series"))).toBe(false);
  });
});

describe("pixelRatio", () => {
  it("超长图降倍率，避免位图内存爆掉", () => {
    expect(pixelRatio(1_000)).toBe(2);
    expect(pixelRatio(6_000)).toBe(1.6);
    expect(pixelRatio(LONG_IMAGE_MAX_HEIGHT)).toBe(1.2);
  });
});

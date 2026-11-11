import { beforeAll, describe, expect, it } from "vitest";
import {
  autofixMermaid,
  AUTOFIX_COMBOS,
  AUTOFIX_RULES,
  describeMermaidError,
} from "./mermaidAutofix";

const FIX = {
  declaration: "补全 flowchart TD 声明",
  fullwidth: "全角标点替换为半角",
  blankLines: "规范化多余空行与行尾空格",
  subgraphEnd: "补全缺失的 subgraph end",
  arrowSpaces: "修复空格断开的箭头 -->",
  nestedParens: "为含括号的节点文本加引号",
};

/** Run a single rule in isolation so hit / no-hit assertions are unambiguous. */
const one = (code: string, id: string) => autofixMermaid(code, [id]);

describe("mermaidAutofix module shape", () => {
  it("exposes the 6 active rules; c/d/e/f are intentionally not implemented", () => {
    expect(AUTOFIX_RULES.map((r) => r.id)).toEqual([
      "missingDeclaration",
      "fullwidthPunct",
      "blankLines",
      "subgraphEnd",
      "arrowSpaces",
      "nestedParens",
    ]);
  });

  it("AUTOFIX_COMBOS is at most 3 rounds, each a shrinking subset", () => {
    expect(AUTOFIX_COMBOS.length).toBeLessThanOrEqual(3);
    expect(AUTOFIX_COMBOS.length).toBe(3);
    for (let i = 1; i < AUTOFIX_COMBOS.length; i++) {
      expect(AUTOFIX_COMBOS[i].length).toBeLessThan(AUTOFIX_COMBOS[i - 1].length);
      for (const id of AUTOFIX_COMBOS[i]) {
        expect(AUTOFIX_COMBOS[i - 1]).toContain(id);
      }
    }
  });

  it("autofixMermaid honours an explicit rule-id subset", () => {
    const r = autofixMermaid("A[B(测试)]-->C", ["missingDeclaration"]);
    expect(r.fixes).toEqual([FIX.declaration]);
    expect(r.code).not.toContain('"'); // nestedParens was not requested
  });

  it("the combo without nestedParens never quotes nested parens", () => {
    const combo = AUTOFIX_COMBOS[1];
    expect(combo).not.toContain("nestedParens");
    const r = autofixMermaid("flowchart TD\n  A[B(测试)]-->C", combo);
    expect(r.fixes).not.toContain(FIX.nestedParens);
  });
});

describe("rule (a) missing diagram declaration", () => {
  it("hit: prepends flowchart TD when the first line is a node/edge", () => {
    const r = one("A[开始] --> B[结束]", "missingDeclaration");
    expect(r.fixes).toEqual([FIX.declaration]);
    expect(r.code).toBe("flowchart TD\nA[开始] --> B[结束]");
  });

  it("hit: keeps leading %% comments above the inserted declaration", () => {
    expect(one("%%note\nA-->B", "missingDeclaration").code).toBe(
      "%%note\nflowchart TD\nA-->B",
    );
  });

  it("no-hit: an existing declaration is recognised case-insensitively", () => {
    expect(one("flowchart TD\n  A-->B", "missingDeclaration").fixes).toEqual([]);
    expect(one("Flowchart LR\n  A-->B", "missingDeclaration").fixes).toEqual([]);
    expect(one("sequenceDiagram\n  A->>B: hi", "missingDeclaration").fixes).toEqual([]);
  });

  it("no-hit: a first line that is neither a declaration nor node-like", () => {
    expect(one("Hello world", "missingDeclaration").fixes).toEqual([]);
  });
});

describe("rule (b) full-width punctuation", () => {
  it("hit: converts （）【】：；， to ASCII on unquoted lines", () => {
    const r = one("flowchart TD\n  A（开始）--> B", "fullwidthPunct");
    expect(r.fixes).toEqual([FIX.fullwidth]);
    expect(r.code).toBe("flowchart TD\n  A(开始)--> B");
  });

  it("hit: 【】 become node brackets", () => {
    expect(one("A【开始】 --> B", "fullwidthPunct").code).toBe("A[开始] --> B");
  });

  it("no-hit: quoted lines are left intact (string contents keep full-width)", () => {
    const code = 'flowchart TD\n  A["你好（世界）"]-->B';
    const r = one(code, "fullwidthPunct");
    expect(r.fixes).toEqual([]);
    expect(r.code).toBe(code);
  });

  it("no-hit: %% comment lines are left intact", () => {
    const code = "%% 注释：全角，标点\nflowchart TD\nA-->B";
    expect(one(code, "fullwidthPunct").fixes).toEqual([]);
  });
});

describe("skip-rules c/d/e/f (no safe automatic fix)", () => {
  it("(c) Chinese node ids are not renamed; legal labels are untouched", () => {
    const illegal = "flowchart TD\n  中文-->B";
    const legal = "flowchart TD\n  A[中文]-->B";
    expect(autofixMermaid(illegal).fixes).toEqual([]);
    expect(autofixMermaid(illegal).code).toBe(illegal);
    expect(autofixMermaid(legal).fixes).toEqual([]);
  });

  it("(d) trailing semicolons are harmless and left alone", () => {
    expect(autofixMermaid("flowchart TD\n  A-->B;").fixes).toEqual([]);
  });

  it("(e) ->> in sequenceDiagram is legal and left alone", () => {
    const code = "sequenceDiagram\n  Alice->>Bob: hi";
    expect(autofixMermaid(code).fixes).toEqual([]);
    expect(autofixMermaid(code).code).toBe(code);
  });

  it("(f) %% comments without a space are legal and left alone", () => {
    const code = "%%注释无空格\nflowchart TD\n  A-->B";
    expect(autofixMermaid(code).fixes).toEqual([]);
  });
});

describe("rule (g) blank lines / trailing whitespace", () => {
  it("hit: collapses multiple blank lines and strips trailing spaces", () => {
    const r = one("flowchart TD\n\n\n\n  A-->B   ", "blankLines");
    expect(r.fixes).toEqual([FIX.blankLines]);
    expect(r.code).toBe("flowchart TD\n  A-->B");
  });

  it("no-hit: clean single-newline code is unchanged", () => {
    expect(one("flowchart TD\n  A-->B", "blankLines").fixes).toEqual([]);
  });
});

describe("rule (h) subgraph / end balance", () => {
  it("hit: appends a missing end", () => {
    const r = one("flowchart TD\n  subgraph X\n  A-->B", "subgraphEnd");
    expect(r.fixes).toEqual([FIX.subgraphEnd]);
    expect(r.code).toBe("flowchart TD\n  subgraph X\n  A-->B\nend");
  });

  it("hit: appends one end per unclosed subgraph", () => {
    const r = one(
      "flowchart TD\nsubgraph A\nA1-->A2\nsubgraph B\nB1-->B2",
      "subgraphEnd",
    );
    expect(r.code.endsWith("\nend\nend")).toBe(true);
  });

  it("no-hit: balanced subgraph/end is unchanged", () => {
    expect(
      one("flowchart TD\nsubgraph X\nA-->B\nend", "subgraphEnd").fixes,
    ).toEqual([]);
  });
});

describe("rule (i) arrows broken by a space", () => {
  it("hit: -- > and - > become -->", () => {
    const a = one("flowchart TD\n  A -- > B", "arrowSpaces");
    expect(a.fixes).toEqual([FIX.arrowSpaces]);
    expect(a.code).toBe("flowchart TD\n  A --> B");
    expect(one("flowchart TD\n  A - > B", "arrowSpaces").code).toBe(
      "flowchart TD\n  A --> B",
    );
  });

  it("no-hit: sequence ->> and normal --> are untouched", () => {
    expect(one("sequenceDiagram\n  Alice->>Bob: hi", "arrowSpaces").fixes).toEqual([]);
    expect(one("flowchart TD\n  A-->B", "arrowSpaces").fixes).toEqual([]);
  });
});

describe("rule (j) nested parens in square-bracket node text", () => {
  it("hit: quotes the node text so the parens are literal", () => {
    const r = one("flowchart TD\n  A[B(测试)]-->C", "nestedParens");
    expect(r.fixes).toEqual([FIX.nestedParens]);
    expect(r.code).toBe('flowchart TD\n  A["B(测试)"]-->C');
  });

  it("no-hit: already-quoted text, parens-free brackets, rounded nodes", () => {
    expect(
      one('flowchart TD\n  A["B(测试)"]-->C', "nestedParens").fixes,
    ).toEqual([]);
    expect(one("flowchart TD\n  A[B测试]-->C", "nestedParens").fixes).toEqual([]);
    expect(one("flowchart TD\n  A(测试)-->C", "nestedParens").fixes).toEqual([]);
  });
});

describe("describeMermaidError", () => {
  it("extracts line + Expecting reason from a parse error", () => {
    const msg =
      "Parse error on line 3:\n...TD  A--->B--->C(((\n-----^\nExpecting 'SQE', got '1'";
    const info = describeMermaidError(msg);
    expect(info.line).toBe(3);
    expect(info.reason).toContain("Expecting");
  });

  it("extracts line from a lexical error", () => {
    const msg =
      "Lexical error on line 2. Unrecognized text.\nflowchart TD  A（开始）--> B\n-----^";
    const info = describeMermaidError(msg);
    expect(info.line).toBe(2);
    expect(info.reason).toBe("Unrecognized text");
  });

  it("handles 'No diagram type detected' with no line number", () => {
    const info = describeMermaidError(
      "No diagram type detected matching given configuration for text: A[开始] --> B[结束]",
    );
    expect(info.line).toBeNull();
    expect(info.reason).toContain("No diagram type detected");
  });

  it("never returns an empty reason", () => {
    expect(describeMermaidError("").reason.length).toBeGreaterThan(0);
  });
});

describe("real AI error samples (autofixed output must pass mermaid.parse)", () => {
  let parse: (code: string) => Promise<unknown>;

  beforeAll(async () => {
    const mermaid = (await import("mermaid")).default;
    mermaid.initialize({ startOnLoad: false, securityLevel: "strict" });
    parse = (code: string) => mermaid.parse(code);
  });

  /** Mirror MermaidCard's combo loop: first combo whose output parses wins. */
  const firstParsingFix = async (code: string) => {
    for (const combo of AUTOFIX_COMBOS) {
      const attempt = autofixMermaid(code, combo);
      if (attempt.fixes.length === 0) continue;
      try {
        await parse(attempt.code);
        return attempt;
      } catch {
        /* try the next rule combination */
      }
    }
    return null;
  };

  // ≥8 representative LLM mistakes. Each must have at least one combo that
  // produces parse-valid mermaid, proving the repair is real, not cosmetic.
  const samples: Array<{ name: string; code: string }> = [
    { name: "缺声明行", code: "A[开始] --> B[结束]" },
    { name: "全角括号", code: "flowchart TD\n  A（开始）--> B" },
    { name: "全角方括号 + 缺声明", code: "A【开始】 --> B【结束】" },
    { name: "subgraph 缺 end", code: "flowchart TD\n  subgraph X\n  A-->B" },
    { name: "箭头空格断开 (-- >)", code: "flowchart TD\n  A -- > B" },
    { name: "多空行", code: "flowchart TD\n\n\n\n  A-->B" },
    { name: "节点文本嵌套括号", code: "flowchart TD\n  A[B(测试)]-->C" },
    { name: "声明+全角+箭头 组合", code: "A【开始】 -- > B（结束）" },
    { name: "混合正常/断开箭头", code: "flowchart TD\n  A --> B\n  C -- > D" },
    { name: "subgraph 缺 end + 断开箭头", code: "flowchart TD\nsubgraph X\nA -- > B" },
  ];

  for (const s of samples) {
    it(`autofixes and the result parses: ${s.name}`, async () => {
      const fixed = await firstParsingFix(s.code);
      expect(fixed).not.toBeNull();
      expect(fixed!.fixes.length).toBeGreaterThan(0);
    }, 30000);
  }

  it("combination fix: declaration + full-width + arrow applied together", () => {
    const r = autofixMermaid("A【开始】 -- > B（结束）");
    expect(r.fixes).toContain(FIX.declaration);
    expect(r.fixes).toContain(FIX.fullwidth);
    expect(r.fixes).toContain(FIX.arrowSpaces);
    expect(r.code).toBe("flowchart TD\nA[开始] --> B(结束)");
  });
});

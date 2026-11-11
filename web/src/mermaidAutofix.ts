/**
 * Rule-based auto-repair for ```mermaid source emitted by LLMs.
 *
 * This module is a pure function layer: no DOM, no async, no mermaid import.
 * `MermaidCard` owns the async `mermaid.parse` validation that decides whether
 * a repaired candidate is actually usable. Keeping the rules pure makes them
 * trivial to unit-test and keeps the "only render what parses" gate in one
 * place.
 *
 * Rules are ordered by how often LLMs get them wrong. Several "known"
 * mistakes are deliberately NOT repaired (they cannot be fixed safely without
 * renaming nodes) — see SKIPPED_RULES below; those cases fall through to the
 * structured error path so the agent can fix the diagram on its next turn.
 */

export interface AutofixResult {
  code: string;
  fixes: string[];
}

export interface AutofixRule {
  id: string;
  /** Human-readable (Chinese) description appended to `fixes` on a hit. */
  label: string;
  apply: (code: string) => { code: string; changed: boolean };
}

/** Diagram declarations recognized on the first meaningful line. */
const DIAGRAM_DECLARATION =
  /^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram(-v2)?|erDiagram|gantt|pie|mindmap|timeline|journey|gitGraph|quadrantChart|requirementDiagram|c4(Context|Container|Component|Dynamic|Deployment)|sankey-beta|xychart-beta|block-beta|packet-beta|architecture-beta)\b/i;

/** Full-width punctuation -> ASCII. Only applied on unquoted, non-comment lines. */
const FULLWIDTH_MAP: Record<string, string> = {
  "（": "(",
  "）": ")",
  "【": "[",
  "】": "]",
  "：": ":",
  "；": ";",
  "，": ",",
};
const FULLWIDTH_RE = /[（）【】：；，]/g;

function splitLines(code: string): string[] {
  return code.split("\n");
}

/** First non-empty line that is not a `%%` comment / directive. */
function firstMeaningfulIndex(lines: string[]): number {
  for (let i = 0; i < lines.length; i++) {
    const s = lines[i].trim();
    if (!s || s.startsWith("%%")) continue;
    return i;
  }
  return -1;
}

// (a) Missing diagram declaration. If the first line looks like a node/edge
// definition but has no declaration, prepend a flowchart declaration.
const missingDeclaration: AutofixRule = {
  id: "missingDeclaration",
  label: "补全 flowchart TD 声明",
  apply(code) {
    const lines = splitLines(code);
    const idx = firstMeaningfulIndex(lines);
    if (idx < 0) return { code, changed: false };
    const first = lines[idx].trim();
    if (DIAGRAM_DECLARATION.test(first)) return { code, changed: false };
    // "looks like a node definition": contains an arrow or a bracket / paren /
    // colon. Full-width variants count too (they get normalized by rule b).
    if (!/[\[(:（）【】：]|-->/u.test(first)) return { code, changed: false };
    const out = lines.slice();
    out.splice(idx, 0, "flowchart TD");
    return { code: out.join("\n"), changed: true };
  },
};

// (b) Full-width punctuation on node/edge lines. Simplification from the
// brief: leave any line containing a quote alone (string contents may
// legitimately contain full-width chars), and never touch `%%` comment lines.
const fullwidthPunct: AutofixRule = {
  id: "fullwidthPunct",
  label: "全角标点替换为半角",
  apply(code) {
    let changed = false;
    const out = splitLines(code).map((line) => {
      if (line.trimStart().startsWith("%%") || /["']/u.test(line)) return line;
      const replaced = line.replace(FULLWIDTH_RE, (ch) => FULLWIDTH_MAP[ch] ?? ch);
      if (replaced !== line) changed = true;
      return replaced;
    });
    return { code: out.join("\n"), changed };
  },
};

// (g) Normalize abnormal blank lines / trailing spaces between the declaration
// and the edges: collapse runs of multiple newlines into a single newline and
// strip trailing horizontal whitespace (including CR from CRLF input).
const blankLines: AutofixRule = {
  id: "blankLines",
  label: "规范化多余空行与行尾空格",
  apply(code) {
    const trimmed = splitLines(code).map((l) => l.replace(/[ \t\r]+$/g, ""));
    const joined = trimmed.join("\n");
    const out = joined.replace(/\n{2,}/g, "\n");
    return { code: out, changed: out !== code };
  },
};

// (h) `subgraph` blocks left unclosed. Append the missing `end` line(s).
const subgraphEnd: AutofixRule = {
  id: "subgraphEnd",
  label: "补全缺失的 subgraph end",
  apply(code) {
    const lines = splitLines(code);
    let subgraphs = 0;
    let ends = 0;
    for (const line of lines) {
      const s = line.trim();
      if (/^subgraph\b/iu.test(s)) subgraphs++;
      else if (/^end\s*;?\s*$/iu.test(s)) ends++;
    }
    const missing = subgraphs - ends;
    if (missing <= 0) return { code, changed: false };
    const out = lines.slice();
    for (let i = 0; i < missing; i++) out.push("end");
    return { code: out.join("\n"), changed: true };
  },
};

// (i) Arrows broken by an inserted space: `-- >` / `- >` -> `-->`.
// Bare `->` and `->>` (legal in sequenceDiagram) are intentionally untouched
// because there is no whitespace between the dash(es) and `>`.
const arrowSpaces: AutofixRule = {
  id: "arrowSpaces",
  label: "修复空格断开的箭头 -->",
  apply(code) {
    const out = code.replace(/-{1,2}[ \t]+>/g, "-->");
    return { code: out, changed: out !== code };
  },
};

// (j) Unescaped nested parentheses inside square-bracket node text.
// `A[B(测试)]` -> `A["B(测试)"]` (quotes make the parens literal).
// Already-quoted contents and brackets without parens are left alone.
const nestedParens: AutofixRule = {
  id: "nestedParens",
  label: "为含括号的节点文本加引号",
  apply(code) {
    const out = code.replace(/\[([^\]"]*)\]/g, (match, inner: string) => {
      if (!/[()]/u.test(inner)) return match;
      if (/^".*"$/u.test(inner)) return match;
      return `["${inner}"]`;
    });
    return { code: out, changed: out !== code };
  },
};

/**
 * Ordered rules. c/d/e/f from the brief are deliberately NOT implemented:
 *  - (c) Chinese node ids (`中文-->B`) cannot be renamed safely.
 *  - (d) trailing semicolons are harmless.
 *  - (e) `->>` is legal in sequenceDiagram.
 *  - (f) `%%` comments without a space are legal.
 * `autofixMermaid` therefore returns these inputs unchanged; the structured
 * error path hands them back to the agent instead.
 */
export const AUTOFIX_RULES: readonly AutofixRule[] = [
  missingDeclaration, // a
  fullwidthPunct, // b
  blankLines, // g
  subgraphEnd, // h
  arrowSpaces, // i
  nestedParens, // j
];

const ALL_RULE_IDS: readonly string[] = AUTOFIX_RULES.map((r) => r.id);

/**
 * Rule combinations tried by `MermaidCard`, in order, at most 3 rounds total.
 * If the full fix does not parse, retry without the riskiest transformations
 * (quote-wrapping, then full-width rewriting) so a single over-eager rule
 * cannot block an otherwise valid repair.
 */
export const AUTOFIX_COMBOS: readonly (readonly string[])[] = [
  ALL_RULE_IDS,
  ALL_RULE_IDS.filter((id) => id !== "nestedParens"),
  ALL_RULE_IDS.filter((id) => id !== "nestedParens" && id !== "fullwidthPunct"),
];

/**
 * Apply the requested rules (all by default) in order, chaining each rule's
 * output into the next. Returns the repaired code plus the list of Chinese
 * fix descriptions for rules that actually changed something.
 */
export function autofixMermaid(
  code: string,
  ruleIds?: readonly string[],
): AutofixResult {
  const active = ruleIds
    ? AUTOFIX_RULES.filter((r) => ruleIds.includes(r.id))
    : AUTOFIX_RULES;
  let current = code;
  const fixes: string[] = [];
  for (const rule of active) {
    const result = rule.apply(current);
    if (result.changed) {
      current = result.code;
      fixes.push(rule.label);
    }
  }
  return { code: current, fixes };
}

export interface MermaidErrorInfo {
  line: number | null;
  reason: string;
}

/**
 * Extract a line number and a short reason from a `mermaid.parse` error
 * message. Handles the common mermaid v11 shapes:
 *  - "Parse error on line 3:\n...\nExpecting 'X', got 'Y'"
 *  - "Lexical error on line 2. Unrecognized text.\n..."
 *  - "No diagram type detected matching given configuration for text: ..."
 */
export function describeMermaidError(message: string): MermaidErrorInfo {
  const lineMatch =
    /(?:Parse error|Lexical error) on line (\d+)/iu.exec(message) ??
    /\bline[:\s]+(\d+)/iu.exec(message);
  const line = lineMatch ? Number(lineMatch[1]) : null;

  const lines = message
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
  let reason = "";
  const expecting = lines.find(
    (l) => /^Expecting\b/iu.test(l) || /^got\b/iu.test(l),
  );
  if (expecting) {
    reason = expecting;
  } else if (lines.some((l) => /Unrecognized text/iu.test(l))) {
    reason = "Unrecognized text";
  } else {
    reason = lines[0] ?? message.trim();
  }
  if (!reason) reason = message.trim() || "Unknown parse error";
  return { line, reason };
}

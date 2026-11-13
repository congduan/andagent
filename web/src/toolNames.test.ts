import { describe, expect, it } from "vitest";
import { BUILTIN_TOOL_NAMES, uniqueToolsByName } from "./agent";

describe("tool names", () => {
  it("内置工具名不重复（同名 tool 会让部分网关直接 400）", () => {
    const dups = BUILTIN_TOOL_NAMES.filter((name, i) => BUILTIN_TOOL_NAMES.indexOf(name) !== i);
    expect(dups).toEqual([]);
  });

  it("发送前按名字去重，先出现者优先", () => {
    const out = uniqueToolsByName([{ name: "a", v: 1 }, { name: "b", v: 2 }, { name: "a", v: 3 }]);
    expect(out).toEqual([{ name: "a", v: 1 }, { name: "b", v: 2 }]);
  });
});

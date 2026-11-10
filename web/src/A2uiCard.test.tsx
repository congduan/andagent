import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { A2uiCard } from "./A2uiCard";

function captureFill() {
  const handler = vi.fn();
  const listener = (event: Event) => handler(event);
  window.addEventListener("andagent:composer-fill", listener);
  return { handler, stop: () => window.removeEventListener("andagent:composer-fill", listener) };
}

describe("A2uiCard", () => {
  beforeEach(() => localStorage.setItem("andagent.language", "zh"));
  afterEach(() => cleanup());

  it("renders an error card for invalid JSON instead of throwing", () => {
    render(<A2uiCard code={"{not json"} />);
    expect(document.querySelector(".a2ui-error")).toBeTruthy();
    expect(screen.getByText("表单卡数据格式错误")).toBeTruthy();
  });

  it("renders catalog fields and dispatches composer-fill on submit", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "导出设置",
      fields: [
        { type: "TEXT_INPUT", key: "email", label: "邮箱", required: true },
        { type: "NUMBER_SLIDER", key: "count", label: "数量", min: 1, max: 10, default: 3 },
        { type: "CHOICE", key: "format", label: "格式", options: ["Markdown", "PDF"] },
        { type: "DATE_PICK", key: "date", label: "日期" },
        { type: "SWITCH", key: "images", label: "包含图片", default: true },
        {
          type: "BUTTON_GROUP",
          key: "scope",
          label: "范围",
          buttons: [{ value: "all", label: "全部" }],
        },
        { type: "CARD", title: "说明", text: "仅用于测试" },
        { type: "ROW", children: [{ type: "LABEL", text: "行内标签" }] },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);

    expect(screen.getByText("导出设置")).toBeTruthy();
    expect(screen.getByText("行内标签")).toBeTruthy();
    expect(screen.getByText("仅用于测试")).toBeTruthy();
    expect(screen.getByLabelText(/数量/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/邮箱/), { target: { value: "a@b.com" } });
    fireEvent.click(screen.getByLabelText("PDF"));
    fireEvent.change(screen.getByLabelText("日期"), { target: { value: "2026-11-06" } });
    fireEvent.click(screen.getByText("全部"));
    fireEvent.click(screen.getByText("提交"));

    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_title).toBe("导出设置");
    expect(detail.a2ui_submit).toEqual({
      email: "a@b.com",
      count: 3,
      format: "PDF",
      date: "2026-11-06",
      images: true,
      scope: "all",
    });
    expect(screen.getByText("已提交")).toBeTruthy();
    stop();
  });

  it("blocks submit when a required field is empty", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [{ type: "TEXT_INPUT", key: "email", label: "邮箱", required: true }],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("此项为必填")).toBeTruthy();
    stop();
  });

  it("degrades an unknown component to a label with one warning", () => {
    const spec = {
      fields: [
        { type: "MAGIC_BOX", key: "mystery", label: "神秘组件" },
        { type: "LABEL", text: "正常文本" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("神秘组件")).toBeTruthy();
    expect(screen.getByText("正常文本")).toBeTruthy();
    expect(screen.getByText("表单包含未知组件，已按只读文本显示。")).toBeTruthy();
  });

  it("degrades nesting deeper than 8 levels without crashing", () => {
    let node: Record<string, unknown> = { type: "TEXT_INPUT", key: "deep", label: "深层字段" };
    for (let i = 0; i < 12; i++) node = { type: "COLUMN", children: [node] };
    render(<A2uiCard code={JSON.stringify({ fields: [node] })} />);
    expect(screen.getByText("嵌套过深，已简化显示")).toBeTruthy();
    expect(document.querySelector(".a2ui-error")).toBeNull();
  });

  it("shows an error card when the node budget is exceeded", () => {
    const fields = Array.from({ length: 61 }, (_, index) => ({
      type: "LABEL",
      text: `L${index}`,
    }));
    render(<A2uiCard code={JSON.stringify({ fields })} />);
    expect(document.querySelector(".a2ui-error")).toBeTruthy();
    expect(screen.getByText("表单组件过多，无法显示")).toBeTruthy();
  });

  it("rejects a catastrophic regex pattern quickly", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "value",
          label: "恶意模式",
          required: true,
          validate: "regex",
          pattern: "^(a+)+$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/恶意模式/), { target: { value: "a".repeat(200) } });
    const start = Date.now();
    fireEvent.click(screen.getByText("提交"));
    expect(Date.now() - start).toBeLessThan(500);
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("rejects a quantified-group ReDoS pattern (`(a?){24}` slips count checks)", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "value",
          label: "组量词模式",
          required: true,
          validate: "regex",
          pattern: "^(a?){24}$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/组量词模式/), { target: { value: "a".repeat(24) + "!" } });
    const start = Date.now();
    fireEvent.click(screen.getByText("提交"));
    expect(Date.now() - start).toBeLessThan(500);
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("keeps only the first instance of a duplicate key", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "name", label: "第一个名称" },
        { type: "TEXT_INPUT", key: "name", label: "第二个名称" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("字段 key 重复，此项已忽略")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("第一个名称"), { target: { value: "first" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ name: "first" });
    stop();
  });

  it("degrades a slider when min is greater than max", () => {
    const spec = {
      fields: [
        { type: "NUMBER_SLIDER", key: "count", label: "数量", min: 10, max: 1, default: 5 },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("滑杆范围无效（min 大于 max），此项已禁用")).toBeTruthy();
    expect(document.querySelector('input[type="range"]')).toBeNull();
  });

  it("blocks submit when a required slider has an invalid range", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "NUMBER_SLIDER", key: "count", label: "数量", required: true, min: 10, max: 1 },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("滑杆范围无效（min 大于 max），此项已禁用")).toBeTruthy();
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    stop();
  });

  it("degrades a value field without a key instead of rendering a dead input", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "TEXT_INPUT", label: "无键输入" },
        { type: "SWITCH", label: "无键开关" },
        { type: "TEXT_INPUT", key: "ok", label: "正常键" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("无键输入")).toBeTruthy();
    expect(screen.getByText("无键开关")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-input")).toHaveLength(1);
    expect(document.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("正常键"), { target: { value: "v" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ ok: "v" });
    stop();
  });

  it("shows an error card when no field is a usable object", () => {
    render(<A2uiCard code={JSON.stringify({ fields: ["a", 42, null] })} />);
    expect(document.querySelector(".a2ui-error")).toBeTruthy();
    expect(screen.getByText("表单卡数据格式错误")).toBeTruthy();
  });

  it("keeps fractional slider steps free of float noise", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "NUMBER_SLIDER", key: "ratio", label: "比例", min: 0, max: 1, step: 0.1, default: 0.3 },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("0.3")).toBeTruthy();
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ ratio: 0.3 });
    stop();
  });

  it("clamps an out-of-range slider default and submits the displayed value", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "NUMBER_SLIDER", key: "count", label: "数量", min: 1, max: 10, step: 0, default: 99 },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("10")).toBeTruthy();
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ count: 10 });
    stop();
  });

  it("blocks submit when a regex pattern cannot compile", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "value", label: "坏模式", validate: "regex", pattern: "(" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/坏模式/), { target: { value: "anything" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("blocks submit entirely when a required choice has no options", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "CHOICE", key: "format", label: "格式", required: true, options: [] },
        { type: "LABEL", text: "说明文本" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("表单配置错误：必选项没有可选项")).toBeTruthy();
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeTruthy();
    stop();
  });

  it("hides fields nested under non-layout containers from validation and submit", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "CARD",
          title: "说明",
          children: [{ type: "TEXT_INPUT", key: "hidden", label: "隐藏字段", required: true }],
        },
        { type: "TEXT_INPUT", key: "visible", label: "可见字段" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByLabelText(/可见字段/)).toBeTruthy();
    expect(screen.queryByLabelText(/隐藏字段/)).toBeNull();
    fireEvent.change(screen.getByLabelText(/可见字段/), { target: { value: "ok" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ visible: "ok" });
    stop();
  });

  it("rejects many-quantifier patterns that are neither nested nor adjacent", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "value",
          label: "多量词模式",
          required: true,
          validate: "regex",
          pattern: "^a*a*a*a*a*b$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/多量词模式/), { target: { value: "a".repeat(1000) } });
    const start = Date.now();
    fireEvent.click(screen.getByText("提交"));
    expect(Date.now() - start).toBeLessThan(500);
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("rejects optional-quantifier patterns (`?` counts toward the budget)", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "value",
          label: "可选量词模式",
          required: true,
          validate: "regex",
          pattern: "^a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?b$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/可选量词模式/), { target: { value: "a".repeat(1000) } });
    const start = Date.now();
    fireEvent.click(screen.getByText("提交"));
    expect(Date.now() - start).toBeLessThan(500);
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("blocks submit when a required choice has only empty-string options", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "CHOICE",
          key: "pick",
          label: "选择",
          required: true,
          options: ["", ""],
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    // “”与全角空格不同：两个选项 value 都是空串 → valueOf 读回 undefined → 必填不可满足
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeTruthy();
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    stop();
  });

  it("allows submit when a required choice has at least one usable option", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "CHOICE", key: "pick", label: "选择", required: true, options: ["", "可用"] },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.queryByText(/表单配置错误：必填项无法填写/)).toBeNull();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeNull();
    fireEvent.click(screen.getByText("可用"));
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse((handler.mock.calls[0][0] as CustomEvent).detail);
    expect(detail.a2ui_submit.pick).toBe("可用");
    stop();
  });

  it("marks a required field with a reserved key as broken", () => {
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "__proto__", label: "保留键", required: true },
        { type: "SWITCH", key: "ok", label: "开关", default: true },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeTruthy();
  });

  it("marks a required field whose key duplicates an earlier field as broken", () => {
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "dupe", label: "第一个" },
        { type: "TEXT_INPUT", key: "dupe", label: "重复的必填项", required: true },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeTruthy();
  });

  it("blocks submit when a required field nested in a ROW has no key", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "ROW",
          children: [{ type: "TEXT_INPUT", label: "无键必填", required: true }],
        },
        { type: "TEXT_INPUT", key: "ok", label: "正常键" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText(/表单配置错误：必填项无法填写/)).toBeTruthy();
    expect(document.querySelector("button.a2ui-submit[disabled]")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("正常键"), { target: { value: "v" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    stop();
  });

  it("rejects three-star patterns at the old budget boundary (`^a*a*a*b$`)", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "value",
          label: "三星模式",
          required: true,
          validate: "regex",
          pattern: "^a*a*a*b$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/三星模式/), { target: { value: "a".repeat(1000) } });
    const start = Date.now();
    fireEvent.click(screen.getByText("提交"));
    expect(Date.now() - start).toBeLessThan(500);
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("格式不符合要求")).toBeTruthy();
    stop();
  });

  it("reads back a 'toString' key as its own value, not an inherited function", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [{ type: "TEXT_INPUT", key: "toString", label: "继承键" }],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/继承键/), { target: { value: "mine" } });
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit.toString).toBe("mine");
    stop();
  });

  it("accepts common patterns within the quantifier budget", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "TEXT_INPUT",
          key: "port",
          label: "端口",
          validate: "regex",
          pattern: "^\\d{1,5}$",
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    fireEvent.change(screen.getByLabelText(/端口/), { target: { value: "8080" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ port: "8080" });
    stop();
  });

  it("degrades and skips reserved prototype keys", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "__proto__", label: "危险键" },
        { type: "TEXT_INPUT", key: "ok", label: "正常键" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("字段 key 为保留名称，此项已忽略")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("正常键"), { target: { value: "safe" } });
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ ok: "safe" });
    stop();
  });

  it("shows an error card when the fence payload is too large", () => {
    render(<A2uiCard code={"x".repeat(100 * 1024 + 1)} />);
    expect(document.querySelector(".a2ui-error")).toBeTruthy();
    expect(screen.getByText("表单卡内容过大，无法显示")).toBeTruthy();
  });

  // R105: prefill — cron/agent forms open with their initial values already set.
  it("renders prefill as the initial value for all five components and submits them untouched", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "晨报",
      fields: [
        { type: "TEXT_INPUT", key: "weather", label: "天气", prefill: "晴 26℃" },
        { type: "NUMBER_SLIDER", key: "focus", label: "专注分钟", min: 0, max: 60, step: 5, default: 10, prefill: 35 },
        { type: "DATE_PICK", key: "date", label: "日期", prefill: "2026-11-08" },
        { type: "CHOICE", key: "mood", label: "状态", options: ["在岗", "请假"], prefill: "在岗" },
        { type: "SWITCH", key: "todo", label: "看待办", default: false, prefill: true },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText(/天气/) as HTMLInputElement).value).toBe("晴 26℃");
    expect((screen.getByLabelText(/专注分钟/) as HTMLInputElement).value).toBe("35");
    expect((screen.getByLabelText(/日期/) as HTMLInputElement).value).toBe("2026-11-08");
    expect((screen.getByLabelText("在岗") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/看待办/) as HTMLInputElement).checked).toBe(true);

    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_title).toBe("晨报");
    expect(detail.a2ui_submit).toEqual({
      weather: "晴 26℃",
      focus: 35,
      date: "2026-11-08",
      mood: "在岗",
      todo: true,
    });
    stop();
  });

  it("pre-checks several options for a multiple CHOICE with an array prefill", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        {
          type: "CHOICE",
          key: "schedule",
          label: "今日日程",
          multiple: true,
          options: ["站会", "评审", "复盘"],
          prefill: ["站会", "复盘"],
        },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText("站会") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("评审") as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText("复盘") as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ schedule: ["站会", "复盘"] });
    stop();
  });

  it("ignores prefill whose type does not match the component", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [
        { type: "TEXT_INPUT", key: "t", label: "文本", prefill: 42 },
        { type: "NUMBER_SLIDER", key: "n", label: "数值", min: 0, max: 10, default: 3, prefill: "7" },
        { type: "SWITCH", key: "s", label: "开关", default: false, prefill: "true" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText(/文本/) as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText(/数值/) as HTMLInputElement).value).toBe("3");
    expect((screen.getByLabelText(/开关/) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    // Slider/Switch fall back to their default; the text field stays empty.
    expect(detail.a2ui_submit).toEqual({ n: 3, s: false });
    stop();
  });

  it("lets the user edit a prefilled value before submitting", () => {
    const { handler, stop } = captureFill();
    const spec = {
      fields: [{ type: "TEXT_INPUT", key: "weather", label: "天气", prefill: "晴" }],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText(/天气/) as HTMLInputElement).value).toBe("晴");
    fireEvent.change(screen.getByLabelText(/天气/), { target: { value: "小雨" } });
    fireEvent.click(screen.getByText("提交"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ weather: "小雨" });
    stop();
  });

  // R105: the three cron a2ui templates shipped in A2UI_GUIDE — same JSON specs,
  // verified end-to-end (prefill renders + submit emits structured values).

  it("R105 cron 模板·晨报：五组件 prefill 渲染并提交结构化值", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "晨报确认",
      submit_label: "提交晨报",
      fields: [
        { type: "DATE_PICK", key: "date", label: "日期", prefill: "2026-11-08" },
        { type: "TEXT_INPUT", key: "weather", label: "天气", prefill: "晴 26℃" },
        { type: "CHOICE", key: "agenda", label: "今日日程", multiple: true, options: ["站会", "评审", "复盘"], prefill: ["站会", "复盘"] },
        { type: "SWITCH", key: "todo", label: "看待办", default: false, prefill: true },
        { type: "NUMBER_SLIDER", key: "focus", label: "专注分钟", min: 0, max: 60, step: 5, default: 25, prefill: 35 },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText(/日期/) as HTMLInputElement).value).toBe("2026-11-08");
    expect((screen.getByLabelText(/天气/) as HTMLInputElement).value).toBe("晴 26℃");
    expect((screen.getByLabelText("站会") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("评审") as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText("复盘") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/看待办/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/专注分钟/) as HTMLInputElement).value).toBe("35");

    fireEvent.click(screen.getByText("提交晨报"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_title).toBe("晨报确认");
    expect(detail.a2ui_submit).toEqual({
      date: "2026-11-08",
      weather: "晴 26℃",
      agenda: ["站会", "复盘"],
      todo: true,
      focus: 35,
    });
    stop();
  });

  it("R105 cron 模板·备份范围：多选目录预填 + 开关 + 备注", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "备份范围",
      submit_label: "开始备份",
      fields: [
        { type: "CHOICE", key: "folders", label: "要备份的目录", multiple: true, options: ["DCIM", "Download", "Documents", "WhatsApp"], prefill: ["DCIM", "Download"] },
        { type: "SWITCH", key: "include_hidden", label: "包含隐藏文件", default: false, prefill: false },
        { type: "TEXT_INPUT", key: "note", label: "备注", prefill: "每周例行" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect((screen.getByLabelText("DCIM") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("Download") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("Documents") as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText(/包含隐藏文件/) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText(/备注/) as HTMLInputElement).value).toBe("每周例行");

    fireEvent.click(screen.getByText("开始备份"));
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({
      folders: ["DCIM", "Download"],
      include_hidden: false,
      note: "每周例行",
    });
    stop();
  });

  it("R105 cron 模板·删除确认：风险 CARD + 必选 BUTTON_GROUP 未选前拦提交，选中后带值提交", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "删除确认",
      submit_label: "提交",
      fields: [
        { type: "CARD", title: "高风险操作", text: "将永久删除所选数据，不可恢复。" },
        { type: "BUTTON_GROUP", key: "confirm", label: "是否继续", required: true, buttons: [{ value: "cancel", label: "取消" }, { value: "delete", label: "确认删除" }] },
        { type: "TEXT_INPUT", key: "reason", label: "删除原因", prefill: "清理旧备份" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("将永久删除所选数据，不可恢复。")).toBeTruthy();
    // 必选 BUTTON_GROUP 未选 → 不派发 composer-fill。
    fireEvent.click(screen.getByText("提交"));
    expect(handler).not.toHaveBeenCalled();
    expect(screen.getByText("此项为必填")).toBeTruthy();
    // 选中「确认删除」后可提交，值随结构化 payload 回灌。
    fireEvent.click(screen.getByText("确认删除"));
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ confirm: "delete", reason: "清理旧备份" });
    stop();
  });

  // R136: read-only display fields (TEXT_DISPLAY / KV_LIST / TABLE / IMAGE /
  // PROGRESS). They never carry a key, never enter VALUE_TYPES, and never
  // participate in value collection, broken detection or the submit payload.

  it("R136 TEXT_DISPLAY renders read-only text and a monospace code block", () => {
    const spec = {
      fields: [
        { type: "TEXT_DISPLAY", label: "设备信息", text: "型号 Pixel\n电量 82%" },
        { type: "TEXT_DISPLAY", text: "```js\nconst x = 1;\n```" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("设备信息")).toBeTruthy();
    expect(screen.getByText(/型号 Pixel/)).toBeTruthy();
    expect(document.querySelector(".a2ui-text-display")).toBeTruthy();
    expect(document.querySelector(".a2ui-text-display-code")).toBeTruthy();
    expect(document.querySelector(".a2ui-submit")).toBeNull();
  });

  it("R136 TEXT_DISPLAY clips text longer than 1000 characters", () => {
    render(
      <A2uiCard
        code={JSON.stringify({ fields: [{ type: "TEXT_DISPLAY", text: "x".repeat(1200) }] })}
      />,
    );
    const el = document.querySelector(".a2ui-text-display") as HTMLElement;
    expect(el.textContent).toHaveLength(1000);
  });

  it("R136 KV_LIST renders a two-column key/value grid and caps items at 32", () => {
    const items = Array.from({ length: 40 }, (_, index) => ({ k: `k${index}`, v: `v${index}` }));
    render(
      <A2uiCard
        code={JSON.stringify({ fields: [{ type: "KV_LIST", label: "状态", items }] })}
      />,
    );
    expect(screen.getByText("状态")).toBeTruthy();
    expect(screen.getByText("k0")).toBeTruthy();
    expect(screen.getByText("v0")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-kv-row")).toHaveLength(32);
    expect(screen.queryByText("k39")).toBeNull();
  });

  it("R136 KV_LIST clips k/v strings to 200 characters", () => {
    render(
      <A2uiCard
        code={JSON.stringify({
          fields: [{ type: "KV_LIST", items: [{ k: "a".repeat(250), v: "b".repeat(250) }] }],
        })}
      />,
    );
    expect((document.querySelector(".a2ui-kv-key") as HTMLElement).textContent).toHaveLength(200);
    expect((document.querySelector(".a2ui-kv-val") as HTMLElement).textContent).toHaveLength(200);
  });

  it("R136 TABLE renders headers/cells, caps rows at 50 and columns at 8", () => {
    const headers = Array.from({ length: 10 }, (_, index) => `H${index}`);
    const rows = Array.from({ length: 60 }, (_, row) =>
      Array.from({ length: 10 }, (_, col) => `R${row}C${col}`),
    );
    render(
      <A2uiCard
        code={JSON.stringify({ fields: [{ type: "TABLE", label: "文件列表", headers, rows }] })}
      />,
    );
    expect(screen.getByText("文件列表")).toBeTruthy();
    expect(screen.getByText("H0")).toBeTruthy();
    expect(screen.getByText("R0C0")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-table thead th")).toHaveLength(8);
    expect(document.querySelectorAll(".a2ui-table tbody tr")).toHaveLength(50);
    expect(document.querySelectorAll(".a2ui-table tbody tr:first-child td")).toHaveLength(8);
  });

  it("R136 TABLE pads short rows and truncates long rows to the header column count", () => {
    render(
      <A2uiCard
        code={JSON.stringify({
          fields: [{ type: "TABLE", headers: ["A", "B", "C"], rows: [["1"], ["1", "2", "3", "4", "5"]] }],
        })}
      />,
    );
    const trs = document.querySelectorAll(".a2ui-table tbody tr");
    expect(trs).toHaveLength(2);
    expect(trs[0].querySelectorAll("td")).toHaveLength(3);
    expect((trs[0].querySelectorAll("td")[1] as HTMLElement).textContent).toBe("");
    expect(trs[1].querySelectorAll("td")).toHaveLength(3);
    expect((trs[1].querySelectorAll("td")[2] as HTMLElement).textContent).toBe("3");
  });

  it("R136 IMAGE rejects http(s):// src and degrades to a placeholder", () => {
    const { unmount } = render(
      <A2uiCard
        code={JSON.stringify({ fields: [{ type: "IMAGE", label: "截图", src: "http://evil.example/x.png" }] })}
      />,
    );
    expect(screen.getByText("截图")).toBeTruthy();
    expect(screen.getByText("图片不可用")).toBeTruthy();
    expect(document.querySelector("img.a2ui-image")).toBeNull();
    unmount();
    render(
      <A2uiCard
        code={JSON.stringify({ fields: [{ type: "IMAGE", src: "https://cdn.example/a.jpg" }] })}
      />,
    );
    expect(screen.getByText("图片不可用")).toBeTruthy();
    expect(document.querySelector("img.a2ui-image")).toBeNull();
  });

  it("R136 IMAGE renders allowed data:image and content:// sources", () => {
    const data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";
    const content = "content://com.example.provider/image/42";
    render(
      <A2uiCard
        code={JSON.stringify({
          fields: [
            { type: "IMAGE", label: "预览", src: data },
            { type: "IMAGE", src: content },
          ],
        })}
      />,
    );
    const imgs = document.querySelectorAll("img.a2ui-image") as NodeListOf<HTMLImageElement>;
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute("src")).toBe(data);
    expect(imgs[1].getAttribute("src")).toBe(content);
    expect(screen.getByText("预览")).toBeTruthy();
    expect(screen.queryByText("图片不可用")).toBeNull();
  });

  it("R136 PROGRESS clamps value to 0-100 and falls back to 0 for a non-number", () => {
    const spec = {
      fields: [
        { type: "PROGRESS", label: "下载", value: 150, detail: "已完成" },
        { type: "PROGRESS", label: "上传", value: "abc" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("下载")).toBeTruthy();
    expect(screen.getByText("已完成")).toBeTruthy();
    const fills = document.querySelectorAll(".a2ui-progress-fill") as NodeListOf<HTMLElement>;
    expect(fills).toHaveLength(2);
    expect(fills[0].style.width).toBe("100%");
    expect(fills[1].style.width).toBe("0%");
  });

  it("R136 a pure display card renders no submit button and no warning", () => {
    const spec = {
      title: "设备概览",
      fields: [
        { type: "TEXT_DISPLAY", text: "在线" },
        { type: "KV_LIST", items: [{ k: "电量", v: "82%" }] },
        { type: "TABLE", headers: ["文件", "大小"], rows: [["a.txt", "1KB"]] },
        { type: "IMAGE", src: "http://x/y.png" },
        { type: "PROGRESS", value: 42, detail: "42%" },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(screen.getByText("设备概览")).toBeTruthy();
    expect(document.querySelector(".a2ui-submit")).toBeNull();
    expect(document.querySelector(".a2ui-actions")).toBeNull();
    expect(document.querySelector(".a2ui-warning")).toBeNull();
    expect(document.querySelector(".a2ui-broken")).toBeNull();
  });

  it("R136 a mixed card keeps submit and its payload contains only input keys", () => {
    const { handler, stop } = captureFill();
    const spec = {
      title: "导出",
      fields: [
        { type: "TEXT_DISPLAY", text: "说明文字" },
        { type: "KV_LIST", items: [{ k: "格式", v: "PDF" }] },
        { type: "TABLE", headers: ["列"], rows: [["值"]] },
        { type: "IMAGE", src: "data:image/png;base64,AAAA" },
        { type: "PROGRESS", value: 100, detail: "done" },
        { type: "TEXT_INPUT", key: "email", label: "邮箱" },
        { type: "SWITCH", key: "notify", label: "通知", default: false },
      ],
    };
    render(<A2uiCard code={JSON.stringify(spec)} />);
    expect(document.querySelector(".a2ui-submit")).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/邮箱/), { target: { value: "a@b.com" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ email: "a@b.com", notify: false });
    stop();
  });
});

// R136: 只读展示字段（TEXT_DISPLAY / KV_LIST / TABLE / IMAGE / PROGRESS）。
describe("A2uiCard · R136 展示型字段", () => {
  beforeEach(() => localStorage.setItem("andagent.language", "zh"));
  afterEach(() => cleanup());

  const show = (fields: unknown[], extra: Record<string, unknown> = {}) =>
    render(<A2uiCard code={JSON.stringify({ fields, ...extra })} />);

  it("TEXT_DISPLAY 渲染只读文本并 clip 到 1000 字；``` 开头按等宽代码块", () => {
    show([
      { type: "TEXT_DISPLAY", label: "说明", text: "电池 78% · 温度 34℃" },
      { type: "TEXT_DISPLAY", label: "原始日志", text: "```log\nline1\nline2" },
      { type: "TEXT_DISPLAY", label: "超长", text: "字".repeat(1200) },
    ]);
    expect(screen.getByText("电池 78% · 温度 34℃")).toBeTruthy();
    const code = document.querySelector("pre.a2ui-text-display-code");
    expect(code).toBeTruthy();
    expect(code!.textContent).toContain("```log");
    const longNode = document.querySelectorAll(".a2ui-text-display")[2];
    expect(longNode.textContent!.length).toBe(1000); // 复用 MAX_REGEX_INPUT clip
    expect(document.querySelector("input")).toBeNull();
    expect(document.querySelector(".a2ui-submit")).toBeNull();
  });

  it("KV_LIST 左键右值两列渲染，clip 到 32 项", () => {
    const items = Array.from({ length: 40 }, (_, i) => ({ k: `键${i}`, v: `值${i}` }));
    show([{ type: "KV_LIST", label: "设备信息", items }]);
    expect(screen.getByText("键0")).toBeTruthy();
    expect(screen.getByText("值0")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-kv-row")).toHaveLength(32);
    expect(screen.queryByText("键32")).toBeNull();
    expect(screen.queryByText("键39")).toBeNull();
  });

  it("TABLE 渲染 headers/rows，clip 列/行/单元格并支持横向滚动容器", () => {
    const headers = Array.from({ length: 10 }, (_, i) => `列${i}`);
    // Keep the JSON well under the 100KB MAX_CODE guard: only the first cell
    // is oversized (to exercise the 200-char clip); the rest stay short so
    // 60 rows x 10 columns still fit and exercise the row/column caps.
    const rows = Array.from({ length: 60 }, (_, r) =>
      headers.map((_, c) => (r === 0 && c === 0 ? "x".repeat(250) : `${r}-${c}`)),
    );
    show([{ type: "TABLE", label: "传感器", headers, rows }]);
    expect(document.querySelector(".a2ui-table-wrap")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-table thead th")).toHaveLength(8);
    expect(document.querySelectorAll(".a2ui-table tbody tr")).toHaveLength(50);
    expect(document.querySelector(".a2ui-table tbody td")!.textContent!.length).toBe(200);
    expect(screen.queryByText("列9")).toBeNull();
  });

  it("IMAGE 只接受 data:image/ 与 content:// 媒体图片，拒绝 http(s)/内联/相对路径", () => {
    const dataSrc = "data:image/png;base64,iVBORw0KGgo=";
    const contentSrc = "content://media/external/images/42";
    show([
      { type: "IMAGE", label: "截图", src: dataSrc },
      { type: "IMAGE", label: "相册", src: contentSrc },
      { type: "IMAGE", label: "https 外链", src: "https://example.com/a.png" },
      { type: "IMAGE", label: "http 外链", src: "http://example.com/a.png" },
      { type: "IMAGE", label: "内联 SVG", src: "data:image/svg+xml,<svg onload=1>" },
      { type: "IMAGE", label: "非图片", src: "data:text/html;base64,PHNjcmlwdD4=" },
      { type: "IMAGE", label: "相对路径", src: "/local/foo.png" },
    ]);
    const imgs = document.querySelectorAll("img.a2ui-image");
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute("src")).toBe(dataSrc);
    expect(imgs[1].getAttribute("src")).toBe(contentSrc);
    expect(document.querySelectorAll(".a2ui-image-fallback")).toHaveLength(5);
  });

  it("PROGRESS 钳制 value 到 0-100，detail 作为右侧小字", () => {
    show([
      { type: "PROGRESS", label: "下载", value: 150, detail: "已完成" },
      { type: "PROGRESS", label: "电量", value: -20 },
    ]);
    const fills = document.querySelectorAll<HTMLElement>(".a2ui-progress-fill");
    expect(fills).toHaveLength(2);
    expect(fills[0].style.width).toBe("100%");
    expect(fills[1].style.width).toBe("0%");
    expect(screen.getByText("已完成")).toBeTruthy();
    expect(document.querySelectorAll(".a2ui-progress-detail")).toHaveLength(1);
  });

  it("纯展示卡（全展示字段）不渲染提交区，submit_label 也忽略", () => {
    show(
      [
        { type: "TEXT_DISPLAY", text: "设备在线" },
        { type: "KV_LIST", items: [{ k: "电量", v: "78%" }] },
        { type: "PROGRESS", value: 62 },
      ],
      { title: "设备状态", submit_label: "确认" },
    );
    expect(screen.getByText("设备状态")).toBeTruthy();
    expect(screen.getByText("设备在线")).toBeTruthy();
    expect(document.querySelector(".a2ui-submit")).toBeNull();
    expect(document.querySelector(".a2ui-actions")).toBeNull();
    expect(screen.queryByText("确认")).toBeNull();
  });

  it("混合卡保留提交，payload 只含输入字段值", () => {
    const { handler, stop } = captureFill();
    show([
      { type: "KV_LIST", label: "只读", items: [{ k: "k", v: "v" }] },
      { type: "PROGRESS", value: 50 },
      { type: "TEXT_INPUT", key: "name", label: "名称" },
      { type: "NUMBER_SLIDER", key: "count", label: "数量", min: 0, max: 10, default: 3 },
    ]);
    expect(document.querySelector(".a2ui-submit")).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/名称/), { target: { value: "abc" } });
    fireEvent.click(screen.getByText("提交"));
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = JSON.parse(handler.mock.calls[0][0].detail as string);
    expect(detail.a2ui_submit).toEqual({ name: "abc", count: 3 });
    stop();
  });

  it("超限 items>32 / rows>50 走 clip 防御，整卡不报废、不触发 broken", () => {
    const items = Array.from({ length: 40 }, (_, i) => ({ k: `k${i}`, v: `v${i}` }));
    const rows = Array.from({ length: 60 }, (_, i) => [`r${i}`]);
    show([
      { type: "KV_LIST", label: "键值", items },
      { type: "TABLE", label: "表格", headers: ["A"], rows },
    ]);
    expect(document.querySelector(".a2ui-error")).toBeNull();
    expect(document.querySelector(".a2ui-broken")).toBeNull();
    expect(document.querySelectorAll(".a2ui-kv-row")).toHaveLength(32);
    expect(document.querySelectorAll(".a2ui-table tbody tr")).toHaveLength(50);
    expect(document.querySelector(".a2ui-submit")).toBeNull();
  });
});

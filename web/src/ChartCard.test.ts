import { describe, expect, it } from "vitest";
import type { EChartsCoreOption } from "echarts/core";
import { normalizeOption } from "./ChartCard";

const barOption = () => ({
  legend: {},
  xAxis: { type: "category", data: ["一月", "二月", "三月"] },
  yAxis: { type: "value" },
  series: [{ name: "照片数", type: "bar", data: [12, 20, 8] }],
});

describe("ChartCard normalizeOption", () => {
  it("reserves margin inside the canvas so axis labels are not clipped", () => {
    const option = normalizeOption(barOption() as EChartsCoreOption) as any;
    expect(option.grid[0].containLabel).toBe(true);
    expect(option.grid[0].left).toBeGreaterThan(0);
    expect(option.grid[0].right).toBeGreaterThan(0);
    // title/legend band (legend only here) must sit above the plot area
    expect(option.grid[0].top).toBeGreaterThanOrEqual(30);
  });

  it("keeps author-provided grid geometry but still contains labels", () => {
    const option = normalizeOption({
      ...barOption(),
      grid: { left: 40, bottom: 24, containLabel: false },
    } as EChartsCoreOption) as any;
    expect(option.grid[0].left).toBe(40);
    expect(option.grid[0].bottom).toBe(24);
    expect(option.grid[0].containLabel).toBe(true);
  });

  it("turns horizontal legends into scroll so long entries are not cropped", () => {
    const option = normalizeOption(barOption() as EChartsCoreOption) as any;
    expect(option.legend.type).toBe("scroll");
    expect(option.legend.top).toBe(4);
  });

  it("keeps vertical legends centred", () => {
    const option = normalizeOption({
      ...barOption(),
      legend: { orient: "vertical", right: 0 },
    } as EChartsCoreOption) as any;
    expect(option.legend.top).toBeUndefined();
  });

  it("hides overlapping category labels", () => {
    const option = normalizeOption(barOption() as EChartsCoreOption) as any;
    expect(option.xAxis.axisLabel.hideOverlap).toBe(true);
  });

  it("shrinks the pie when a legend shares the card", () => {
    const option = normalizeOption({
      legend: {},
      series: [{ type: "pie", data: [{ name: "A", value: 1 }] }],
    } as EChartsCoreOption) as any;
    expect(option.series[0].radius).toEqual(["0%", "58%"]);
    expect(option.grid).toBeUndefined();
  });
});

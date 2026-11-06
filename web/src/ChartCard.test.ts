import { describe, expect, it } from "vitest";
import type { EChartsCoreOption } from "echarts/core";
import { chartHeight, isHorizontalBar, normalizeOption } from "./ChartCard";

const barOption = () => ({
  legend: {},
  xAxis: { type: "category", data: ["一月", "二月", "三月"] },
  yAxis: { type: "value" },
  series: [{ name: "照片数", type: "bar", data: [12, 20, 8] }],
});

const rankingOption = (count = 3) => ({
  xAxis: { type: "value" },
  yAxis: { type: "category", data: Array.from({ length: count }, (_, i) => `目录 ${i}`) },
  series: [
    {
      type: "bar",
      data: Array.from({ length: count }, (_, i) => i + 1),
      label: { show: true, position: "inside" },
    },
  ],
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

  it("moves horizontal bar labels outside the bar so short values stay readable", () => {
    const option = normalizeOption(rankingOption() as EChartsCoreOption) as any;
    expect(option.series[0].label.position).toBe("right");
    expect(option.series[0].label.color).toBeTruthy();
    // room for the label that now lives past the bar end
    expect(option.grid[0].right).toBeGreaterThanOrEqual(40);
  });

  it("truncates long category names on horizontal layouts", () => {
    const option = normalizeOption(rankingOption() as EChartsCoreOption) as any;
    expect(option.yAxis.axisLabel.width).toBe(104);
    expect(option.yAxis.axisLabel.overflow).toBe("truncate");
  });

  it("leaves vertical bar labels untouched", () => {
    const option = normalizeOption({
      ...barOption(),
      series: [{ type: "bar", data: [1, 2, 3], label: { show: true, position: "inside" } }],
    } as EChartsCoreOption) as any;
    expect(option.series[0].label.position).toBe("inside");
    expect(option.yAxis.axisLabel?.width).toBeUndefined();
  });

  it("does not show labels the author hid", () => {
    const option = normalizeOption({
      ...rankingOption(),
      series: [{ type: "bar", data: [1, 2, 3], label: { show: false, position: "inside" } }],
    } as EChartsCoreOption) as any;
    expect(option.series[0].label.position).toBe("inside");
  });

  it("detects horizontal bar layouts", () => {
    expect(isHorizontalBar(rankingOption() as EChartsCoreOption)).toBe(true);
    expect(isHorizontalBar(barOption() as EChartsCoreOption)).toBe(false);
  });

  it("grows the canvas with the number of ranked rows", () => {
    expect(chartHeight(rankingOption(3) as EChartsCoreOption)).toBe(320);
    expect(chartHeight(rankingOption(6) as EChartsCoreOption)).toBe(320);
    expect(chartHeight(rankingOption(10) as EChartsCoreOption)).toBe(140 + 10 * 32);
    expect(chartHeight(rankingOption(40) as EChartsCoreOption)).toBe(720);
    expect(chartHeight(barOption() as EChartsCoreOption)).toBe(320);
  });
});

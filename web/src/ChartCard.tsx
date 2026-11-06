import { translate, useI18n } from "./i18n";
import { useEffect, useRef, useState } from "react";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsCoreOption } from "echarts/core";

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  CanvasRenderer,
]);

/** Dark theme defaults so model-provided options stay readable in the app. */
const DARK_THEME = {
  backgroundColor: "transparent",
  color: ["#7f9bff", "#4fd1a1", "#ffb95f", "#ff7a7a", "#a07bff", "#4fc3f7"],
  textStyle: { color: "#c9d1e3" },
  title: { textStyle: { color: "#eef1f8" }, subtextStyle: { color: "#8b93a3" } },
  legend: { textStyle: { color: "#aab3c6" } },
  categoryAxis: {
    axisLine: { lineStyle: { color: "rgba(255,255,255,0.18)" } },
    axisLabel: { color: "#8b93a3" },
    splitLine: { lineStyle: { color: "rgba(255,255,255,0.05)" } },
  },
  valueAxis: {
    axisLine: { lineStyle: { color: "rgba(255,255,255,0.18)" } },
    axisLabel: { color: "#8b93a3" },
    splitLine: { lineStyle: { color: "rgba(255,255,255,0.06)" } },
  },
};

const ALLOWED_TYPES = new Set(["bar", "line", "pie"]);
const CARTESIAN_TYPES = new Set(["bar", "line"]);

type LooseOption = Record<string, any>;

function asList<T>(value: T | T[] | null | undefined): T[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

/**
 * ECharts defaults assume a desktop canvas: `grid` keeps only 10% margins and
 * `containLabel` is off, so on a phone-width card the axis labels (long numbers,
 * rotated category names) and over-long legends are painted past the canvas
 * edge and silently cut off. Normalize the model-provided option so everything
 * stays inside the card, while still honoring explicit author settings.
 */
export function normalizeOption(option: EChartsCoreOption): EChartsCoreOption {
  const opt = option as LooseOption;
  const series = asList<LooseOption>(opt.series);
  const titles = asList<LooseOption>(opt.title);
  const legends = asList<LooseOption>(opt.legend);
  const hasCartesian = series.some((s) => CARTESIAN_TYPES.has(String(s?.type)));
  const hasPie = series.some((s) => s?.type === "pie");

  // Space taken by title/legend above the plot area; grid.top must clear it,
  // otherwise the plot starts underneath them.
  const headerHeight = 8 + (titles.length ? 30 : 0) + (legends.length ? 26 : 0);

  for (const title of titles) {
    if (title.top === undefined) title.top = 8;
    if (title.left === undefined && title.right === undefined) title.left = "center";
  }

  for (const legend of legends) {
    // Horizontal legends overflow the card width silently; scroll keeps every
    // entry reachable instead of cropping the tail.
    if (legend.type === undefined) legend.type = "scroll";
    if (legend.top === undefined && legend.bottom === undefined && legend.orient !== "vertical") {
      legend.top = titles.length ? 32 : 4;
    }
    if (legend.itemGap === undefined) legend.itemGap = 12;
  }

  if (hasCartesian) {
    const grids = opt.grid === undefined ? [{}] : asList<LooseOption>(opt.grid);
    opt.grid = grids.map((grid) => ({
      left: 10,
      right: 12,
      top: headerHeight,
      bottom: 8,
      ...grid,
      // Reserve room for axis labels inside the canvas instead of letting them
      // spill over the edge.
      containLabel: true,
    }));
    for (const axis of [...asList<LooseOption>(opt.xAxis), ...asList<LooseOption>(opt.yAxis)]) {
      if (!axis || typeof axis !== "object") continue;
      if (axis.type !== "category" && axis.data === undefined) continue;
      const axisLabel = axis.axisLabel ?? (axis.axisLabel = {});
      if (axisLabel.hideOverlap === undefined) axisLabel.hideOverlap = true;
    }
  }

  if (hasPie) {
    for (const item of series) {
      if (item?.type !== "pie") continue;
      // Keep the pie clear of the legend/title band so neither is covered.
      if (legends.length) {
        if (item.center === undefined) item.center = ["50%", "56%"];
        if (item.radius === undefined) item.radius = ["0%", "58%"];
      } else if (item.radius === undefined) {
        item.radius = ["0%", "68%"];
      }
    }
  }

  return option;
}

function parseOption(code: string): EChartsCoreOption {
  let parsed: unknown;
  try {
    parsed = JSON.parse(code);
  } catch (e) {
    throw new Error(`${translate("chartParseFailed")}：${e instanceof Error ? e.message : String(e)}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(translate("chartObjectRequired"));
  }
  const option = parsed as Record<string, unknown>;
  const rawSeries = option.series;
  const series = Array.isArray(rawSeries) ? rawSeries : rawSeries ? [rawSeries] : [];
  if (series.length === 0) throw new Error(translate("chartSeriesMissing"));
  for (const entry of series) {
    const type = (entry as { type?: unknown } | null)?.type;
    if (typeof type !== "string" || !ALLOWED_TYPES.has(type)) {
      throw new Error(`${translate("chartUnsupported")}：${String(type ?? translate("unspecified"))} (bar/line/pie)`);
    }
  }
  return option as EChartsCoreOption;
}

/**
 * Renders a ```chart code fence. The body is pure ECharts option JSON — no JS
 * expressions — parsed once per code change; the instance is disposed with the
 * component and resized with its container.
 */
export function ChartCard({ code }: { code: string }) {
  const { t, language } = useI18n();
  const hostRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let option: EChartsCoreOption;
    try {
      option = parseOption(code);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    setError(null);

    let chart: echarts.ECharts | null = null;
    try {
      chart = echarts.init(host, DARK_THEME, { renderer: "canvas" });
      chart.setOption(normalizeOption(option), true);
    } catch (e) {
      chart?.dispose();
      setError(`${t("chartRenderFailed")}：${e instanceof Error ? e.message : String(e)}`);
      return;
    }

    const observer = new ResizeObserver(() => chart?.resize());
    observer.observe(host);
    return () => {
      observer.disconnect();
      chart?.dispose();
    };
  }, [code, language]);

  if (error) {
    return (
      <div className="chart-card chart-error">
        <div className="chart-head">
          <span className="chart-badge">{t("chart")}</span>
          <span className="chart-error-text">{error}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="chart-card">
      <div className="chart-head">
        <span className="chart-badge">{t("chart")}</span>
      </div>
      <div ref={hostRef} className="chart-body" />
    </div>
  );
}

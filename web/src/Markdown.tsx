import { useI18n } from "./i18n";
import { memo, useEffect, useMemo, useRef, useState } from "react";
import {
  MarkdownTextPrimitive,
  type SyntaxHighlighterProps,
} from "@assistant-ui/react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import hljs from "highlight.js";
import { ChartCard } from "./ChartCard";
import { MermaidCard } from "./MermaidCard";
import { MediaCard } from "./MediaCard";
import { SvgCard } from "./SvgCard";
import { PhotosCard, ImageCard, KvCard, TableCard, JsonTreeCard, GeoCard } from "./RichCards";
import { CameraCard } from "./CameraCard";
import { TerminalCard } from "./TerminalCard";
import { CanvasCard } from "./CanvasCard";
import { LiveCard } from "./LiveCard";
import { ShaderCard } from "./ShaderCard";
import { WidgetPreviewCard } from "./WidgetPreviewCard";
import { A2uiCard } from "./A2uiCard";

function CodeBlock({ language, code, components: { Pre, Code } }: SyntaxHighlighterProps) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const kind = (language ?? "").toLowerCase();
  const isChart = ["chart", "photos", "image", "kv", "table", "json-tree", "geo", "audio", "video", "camera", "widget", "canvas", "live", "shader", "terminal", "mermaid", "svg", "a2ui"].includes(kind);

  // 流式中重围栏只渲染占位（防闪烁）。判定源 = 本围栏的 code 是否在 800ms 内
  // 变过：draft 流式的围栏随 delta 持续更新；历史消息的围栏 code 恒定 → 永不
  // 误伤（旧版全局 streaming flag 会让新一轮对话时历史卡片全部变「生成中」）。
  const [codeChanging, setCodeChanging] = useState(false);
  const prevCodeRef = useRef(code);
  const stableTimerRef = useRef<number | null>(null);
  useEffect(() => {
    if (!isChart) { setCodeChanging(false); return; }
    if (prevCodeRef.current !== code) {
      prevCodeRef.current = code;
      setCodeChanging(true);
      if (stableTimerRef.current) window.clearTimeout(stableTimerRef.current);
      stableTimerRef.current = window.setTimeout(() => setCodeChanging(false), 800);
    }
    return () => { if (stableTimerRef.current) window.clearTimeout(stableTimerRef.current); };
  }, [code, isChart]);
  const liveFence = codeChanging && isChart;

  const html = useMemo(() => {
    if (isChart) return "";
    try {
      if (language && hljs.getLanguage(language)) {
        return hljs.highlight(code, { language }).value;
      }
      return hljs.highlightAuto(code).value;
    } catch {
      return code.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]!);
    }
  }, [code, language, isChart]);

  // 流式中重围栏只渲染占位（防闪烁）；流结束后走正常卡片分支。
  if (liveFence) {
    return (
      <div className="fence-placeholder" data-kind={kind}>
        <span className="fence-placeholder-label">{kind} {t("generating")}</span>
      </div>
    );
  }

  // ```chart fences carry ECharts option JSON, not source code.
  if (kind === "chart") return <ChartCard code={code} />;
  if (kind === "mermaid") return <MermaidCard code={code} />;
  if (kind === "svg") return <SvgCard code={code} />;
  if (kind === "photos") return <PhotosCard code={code} />;
  if (kind === "image") return <ImageCard code={code} />;
  if (kind === "kv") return <KvCard code={code} />;
  if (kind === "table") return <TableCard code={code} />;
  if (kind === "json-tree") return <JsonTreeCard code={code} />;
  if (kind === "canvas") return <CanvasCard code={code} />;
  if (kind === "live") return <LiveCard code={code} />;
  if (kind === "shader") return <ShaderCard code={code} />;
  if (kind === "geo") return <GeoCard code={code} />;
  if (kind === "camera") return <CameraCard code={code} />;
  if (kind === "terminal") return <TerminalCard code={code} />;
  if (kind === "widget") return <WidgetPreviewCard code={code} />;
  if (kind === "a2ui") return <A2uiCard code={code} />;
  if (kind === "audio" || kind === "video") {
    try {
      const data = JSON.parse(code) as { uri?: string; title?: string; poster?: string };
      if (typeof data.uri === "string") return <MediaCard kind={kind} uri={data.uri} title={data.title} poster={data.poster} />;
    } catch { /* invalid media JSON */ }
    return <div className="media-error">{t("mediaInvalid")}</div>;
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <div className="code-wrap">
      <div className="code-head">
        <span className="code-lang">{language || "text"}</span>
        <button type="button" className="code-copy" onClick={copy}>
          {copied ? t("copied") : t("copy")}
        </button>
      </div>
      <Pre className="code-block">
        <Code
          className={`hljs language-${language ?? "text"}`}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </Pre>
    </div>
  );
}

const MARKDOWN_COMPONENTS = {
  SyntaxHighlighter: CodeBlock,
  a: (props: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props} target="_blank" rel="noreferrer noopener" />
  ),
};

export const MarkdownText = memo(function MarkdownText() {
  return (
    <MarkdownTextPrimitive
      className="md"
      remarkPlugins={[remarkGfm, remarkMath]}
      rehypePlugins={[rehypeKatex]}
      components={MARKDOWN_COMPONENTS}
      defer
    />
  );
});

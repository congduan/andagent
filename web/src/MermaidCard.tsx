import { useI18n } from "./i18n";
import { useEffect, useRef, useState, useId } from "react";
import { resolvedTheme } from "./theme";
import { ImageLightbox } from "./ImageLightbox";

type MermaidModule = typeof import("mermaid");

let mermaidPromise: Promise<MermaidModule> | null = null;

/** Lazy-load mermaid on first use so it stays out of the main bundle. */
function loadMermaid(): Promise<MermaidModule> {
  if (!mermaidPromise) {
    mermaidPromise = import("mermaid").then((mod) => mod);
  }
  return mermaidPromise;
}

/**
 * ```mermaid fenced code blocks: parse + render to SVG via mermaid.render.
 * Parse/render failures show an error card with the original message so the
 * agent can read it back and fix the diagram in the next turn.
 * Re-renders with the matching mermaid theme when the app theme changes.
 */
export function MermaidCard({ code }: { code: string }) {
  const { t } = useI18n();
  const [svg, setSvg] = useState("");
  const [error, setError] = useState("");
  const [expanded, setExpanded] = useState(false);
  const [themeTick, setThemeTick] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const reactId = useId().replace(/[^a-zA-Z0-9]/g, "");
  const renderSeq = useRef(0);

  useEffect(() => {
    const listener = () => setThemeTick((t) => t + 1);
    window.addEventListener("andagent:themechange", listener);
    return () => window.removeEventListener("andagent:themechange", listener);
  }, []);

  useEffect(() => {
    const seq = ++renderSeq.current;
    let cancelled = false;
    (async () => {
      try {
        const { default: mermaid } = await loadMermaid();
        // Re-initialize per render: the theme follows the app theme.
        mermaid.initialize({
          startOnLoad: false,
          theme: resolvedTheme() === "light" ? "default" : "dark",
          securityLevel: "strict",
        });
        const { svg: rendered } = await mermaid.render(`mermaid-${reactId}-${seq}`, code);
        if (!cancelled && seq === renderSeq.current) {
          setSvg(rendered);
          setError("");
        }
      } catch (e) {
        // mermaid leaves a dangling error element in the DOM on parse failure.
        document.getElementById(`dmermaid-${reactId}-${seq}`)?.remove();
        if (!cancelled && seq === renderSeq.current) {
          setSvg("");
          setError(e instanceof Error ? e.message : String(e));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [code, reactId, themeTick]);

  if (error) {
    return <div className="mermaid-card mermaid-error">
      <div className="mermaid-error-head">{t("mermaidRenderFailed")}</div>
      <pre className="mermaid-error-msg">{error}</pre>
      <pre className="mermaid-source">{code}</pre>
    </div>;
  }

  return <div className="mermaid-card">
    <div
      ref={containerRef}
      className="mermaid-figure"
      role={svg ? "button" : undefined}
      tabIndex={svg ? 0 : undefined}
      aria-label={svg ? t("viewChartFullscreen") : undefined}
      onClick={() => svg && setExpanded(true)}
      onKeyDown={(e) => { if (svg && (e.key === "Enter" || e.key === " ")) setExpanded(true); }}
      dangerouslySetInnerHTML={{ __html: svg }}
    />
    {!svg && !error && <div className="mermaid-loading">{t("renderingChart")}</div>}
    {expanded && svg && <ImageLightbox label={t("chartPreview")} onClose={() => setExpanded(false)}>
      <div className="mermaid-lightbox-body" dangerouslySetInnerHTML={{ __html: svg }} />
    </ImageLightbox>}
  </div>;
}

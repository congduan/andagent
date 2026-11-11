import { useI18n } from "./i18n";
import { useEffect, useRef, useState, useId } from "react";
import { resolvedTheme } from "./theme";
import { ImageLightbox } from "./ImageLightbox";
import { copyText } from "./CopyMenu";
import {
  autofixMermaid,
  AUTOFIX_COMBOS,
  describeMermaidError,
} from "./mermaidAutofix";

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
 * On a parse failure the client first tries rule-based autofix (pure function,
 * validated with mermaid.parse, at most AUTOFIX_COMBOS.length rounds). If no
 * repaired candidate parses, the error card shows structured line/reason info
 * plus a bilingual hint so the agent can fix the diagram in one retry. The
 * original source is always retained and copyable.
 * Re-renders with the matching mermaid theme when the app theme changes.
 */
export function MermaidCard({ code }: { code: string }) {
  const { t } = useI18n();
  const [svg, setSvg] = useState("");
  const [error, setError] = useState("");
  const [fixes, setFixes] = useState<string[]>([]);
  const [copyState, setCopyState] = useState<"idle" | "done" | "failed">("idle");
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

        const id = `mermaid-${reactId}-${seq}`;
        const parseCheck = async (
          src: string,
        ): Promise<{ ok: boolean; message: string }> => {
          try {
            await mermaid.parse(src);
            return { ok: true, message: "" };
          } catch (e) {
            return { ok: false, message: e instanceof Error ? e.message : String(e) };
          }
        };

        // Gate: only try autofix when the original does not parse. Every
        // repaired candidate must itself parse before it is rendered.
        const original = await parseCheck(code);
        let toRender = code;
        let appliedFixes: string[] = [];
        let parseError = "";
        if (!original.ok) {
          parseError = original.message;
          for (const combo of AUTOFIX_COMBOS) {
            const attempt = autofixMermaid(code, combo);
            if (attempt.fixes.length === 0) continue;
            const check = await parseCheck(attempt.code);
            if (!check.ok) continue;
            toRender = attempt.code;
            appliedFixes = attempt.fixes;
            parseError = "";
            break;
          }
        }

        try {
          const { svg: rendered } = await mermaid.render(id, toRender);
          if (!cancelled && seq === renderSeq.current) {
            setSvg(rendered);
            setError("");
            setFixes(appliedFixes);
          }
        } catch (renderErr) {
          // mermaid leaves a dangling error element in the DOM on render failure.
          document.getElementById(`d${id}`)?.remove();
          if (!cancelled && seq === renderSeq.current) {
            setSvg("");
            setFixes([]);
            // Prefer the structured parse error; fall back to the render error
            // when the original parsed fine but rendering itself failed.
            setError(
              parseError ||
                (renderErr instanceof Error ? renderErr.message : String(renderErr)),
            );
          }
        }
      } catch (e) {
        if (!cancelled && seq === renderSeq.current) {
          setSvg("");
          setFixes([]);
          setError(e instanceof Error ? e.message : String(e));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [code, reactId, themeTick]);

  const copySource = async () => {
    const ok = await copyText(code);
    setCopyState(ok ? "done" : "failed");
    window.setTimeout(() => setCopyState("idle"), 1500);
  };

  if (error) {
    const info = describeMermaidError(error);
    return <div className="mermaid-card mermaid-error">
      <div className="mermaid-error-head">{t("mermaidRenderFailed")}</div>
      <div className="mermaid-fix-suggestion">
        <div className="mermaid-fix-suggestion-title">{t("mermaidFixSuggestion")}</div>
        <div className="mermaid-fix-line">
          {info.line !== null ? `${t("mermaidErrorLine", { line: info.line })} · ` : ""}{info.reason}
        </div>
        <div className="mermaid-fix-hint">{t("mermaidFixHint")}</div>
      </div>
      <pre className="mermaid-error-msg">{error}</pre>
      <pre className="mermaid-source">{code}</pre>
      <button type="button" className="mermaid-copy-btn" onClick={copySource}>
        {copyState === "done"
          ? t("mermaidCopyDone")
          : copyState === "failed"
            ? t("mermaidCopyFailed")
            : t("mermaidCopySource")}
      </button>
    </div>;
  }

  return <div className="mermaid-card">
    {fixes.length > 0 && <div className="mermaid-autofix">
      <span className="mermaid-autofix-title">{t("mermaidAutofixed", { count: fixes.length })}</span>
      <span className="mermaid-autofix-list">{fixes.join(" · ")}</span>
    </div>}
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

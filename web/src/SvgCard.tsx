import { translate, useI18n } from "./i18n";
import { useMemo, useState } from "react";
import { execTool } from "./bridge";
import { showInfo, showResult } from "./toast";
import { ImageLightbox } from "./ImageLightbox";

/**
 * ```svg fenced code blocks (R39): render a user/agent-provided SVG inline.
 *
 * Security: the source is parsed with DOMParser and only rendered if the root
 * node is exactly <svg> — script/foreignObject payloads that don't survive a
 * parse round-trip as pure SVG markup are rejected before dangerouslySetInnerHTML.
 * Fullscreen reuses the image lightbox styles; PNG export goes svg → canvas →
 * toBlob → image_save (native album).
 */

export function parseSvg(code: string): { svg: SVGSVGElement | null; error: string | null } {
  const doc = new DOMParser().parseFromString(code, "image/svg+xml");
  const err = doc.querySelector("parsererror");
  if (err) return { svg: null, error: translate("svgParseFailed") + "：" + (err.textContent ?? "").slice(0, 200) };
  const root = doc.documentElement;
  if (!root || root.nodeName.toLowerCase() !== "svg") {
    return { svg: null, error: translate("svgRootInvalid") };
  }
  return { svg: root as unknown as SVGSVGElement, error: null };
}

async function exportPng(svgText: string, filename: string): Promise<string> {
  const { svg } = parseSvg(svgText);
  if (!svg) return `ERROR: ${translate("svgExportInvalid")}`;
  const width = Number(svg.getAttribute("width")) || Number(svg.getAttribute("viewBox")?.split(/\s+/)[2]) || 800;
  const height = Number(svg.getAttribute("height")) || Number(svg.getAttribute("viewBox")?.split(/\s+/)[3]) || 600;
  const scale = Math.min(2, Math.max(1, 1600 / Math.max(width, 1)));
  const blob = new Blob([svgText], { type: "image/svg+xml;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(translate("svgBitmapFailed")));
      image.src = url;
    });
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(width * scale);
    canvas.height = Math.round(height * scale);
    const ctx = canvas.getContext("2d");
    if (!ctx) return `ERROR: ${translate("canvasUnavailable")}`;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    const png = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
    if (!png) return `ERROR: ${translate("pngEncodeFailed")}`;
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error(translate("readPngFailed")));
      reader.readAsDataURL(png);
    });
    const result = await execTool("image_save", {
      base64: dataUrl,
      filename: filename.endsWith(".png") ? filename : `${filename}.png`,
    });
    return result.ok ? result.result : `ERROR: ${result.result}`;
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function SvgCard({ code }: { code: string }) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const parsed = useMemo(() => parseSvg(code), [code]);

  if (parsed.error) {
    return <div className="mermaid-card mermaid-error">
      <div className="mermaid-error-head">{t("svgRenderFailed")}</div>
      <pre className="mermaid-error-msg">{parsed.error}</pre>
      <pre className="mermaid-source">{code.slice(0, 2000)}</pre>
    </div>;
  }

  const name = `svg-${Date.now()}`;
  return <div className="svg-card">
    <div className="chart-head">
      <span className="chart-badge">SVG</span>
      <button type="button" className="code-copy" onClick={() => {
        showInfo(t("exporting"));
        void exportPng(code, name).then((result) => showResult(result.startsWith("ERROR:") ? result : `${t("savedToPhotos")}：${result}`));
      }}>{t("exportPng")}</button>
    </div>
    <div
      className="mermaid-figure"
      role="button"
      tabIndex={0}
      aria-label={t("viewSvgFullscreen")}
      onClick={() => setExpanded(true)}
      onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setExpanded(true); }}
      dangerouslySetInnerHTML={{ __html: code }}
    />
    {expanded && <ImageLightbox label={t("svgPreview")} onClose={() => setExpanded(false)}>
      <div className="mermaid-lightbox-body" dangerouslySetInnerHTML={{ __html: code }} />
    </ImageLightbox>}
  </div>;
}

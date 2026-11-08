import { translate } from "./i18n";
import { useState } from "react";
import { execTool, hasBridge } from "./bridge";
import { importChatImage } from "./chatImages";
import { Icon } from "./icons";
import { selectedText } from "./CopyMenu";
import { showError, showSuccess } from "./toast";
import { ImageLightbox } from "./ImageLightbox";

type Photo = { id?: string; uri?: string; name?: string; thumbnailBase64?: string; thumb?: string };

/** Collapsible JSON tree shared by the ```json-tree fence and tool results. */
export function JsonNode({ label, value, depth = 0 }: { label: string; value: unknown; depth?: number }) {
  const [open, setOpen] = useState(depth < 1);
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    return <div className="json-node" style={{ marginLeft: depth ? 12 : 0 }}>
      <button
        type="button"
        onClick={() => {
          if (selectedText()) return;
          setOpen(!open);
        }}
      >
        {open ? "▾" : "▸"} {label} <span>{Array.isArray(value) ? `[${entries.length}]` : `{${entries.length}}`}</span>
      </button>
      {open && entries.map(([key, child]) => <JsonNode key={key} label={key} value={child} depth={depth + 1} />)}
    </div>;
  }
  return <div className="json-leaf" style={{ marginLeft: depth * 12 }}><span>{label}:</span> {JSON.stringify(value)}</div>;
}

export function JsonTreeCard({ code }: { code: string }) {
  let data: unknown;
  try { data = JSON.parse(code); } catch { return <div className="rich-error">{translate("jsonInvalid")}</div>; }
  return <div className="json-tree-card"><JsonNode label="root" value={data} /></div>;
}

/**
 * Unified table / key-value card (```table and ```kv fences).
 * Accepts: {columns, rows}, an array of objects, a plain object (two-column
 * key/value), a Markdown pipe table, CSV, or `label: value` lines.
 */
type TableData = { columns: string[]; rows: string[][]; keyValue: boolean };

function cell(value: unknown): string {
  if (value === null || value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

function parseTable(code: string): TableData | null {
  const trimmed = code.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (Array.isArray(parsed)) {
        if (parsed.length === 0) return { columns: [], rows: [], keyValue: false };
        if (parsed.every((row) => row && typeof row === "object" && !Array.isArray(row))) {
          const columns = [...new Set(parsed.flatMap((row) => Object.keys(row as object)))];
          return { columns, rows: parsed.map((row) => columns.map((c) => cell((row as Record<string, unknown>)[c]))), keyValue: false };
        }
        return {
          columns: parsed.map((_, i) => String(i + 1)),
          rows: [parsed.map(cell)],
          keyValue: false,
        };
      }
      if (parsed && typeof parsed === "object") {
        const obj = parsed as Record<string, unknown>;
        if (Array.isArray(obj.columns) && Array.isArray(obj.rows)) {
          const columns = obj.columns.map(cell);
          return { columns, rows: obj.rows.map((row) => Array.isArray(row) ? row.map(cell) : [cell(row)]), keyValue: false };
        }
        return { columns: [translate("field"), translate("value")], rows: Object.entries(obj).map(([k, v]) => [k, cell(v)]), keyValue: true };
      }
    } catch {
      return null;
    }
  }
  const lines = trimmed.split("\n").map((line) => line.trim()).filter(Boolean);
  const split = (line: string): string[] =>
    line.replace(/^\s*\|/, "").replace(/\|\s*$/, "").split("|").map((s) => s.trim());
  if (lines[0].includes("|") && lines.length > 1 && /^[\s|:-]+$/.test(lines[1])) {
    const columns = split(lines[0]);
    return { columns, rows: lines.slice(2).map(split), keyValue: false };
  }
  const pairs = lines.filter((line) => !line.includes("|") && line.includes(":"))
    .map((line) => { const at = line.indexOf(":"); return [line.slice(0, at).trim(), line.slice(at + 1).trim()] as [string, string]; });
  if (pairs.length === lines.length && pairs.length > 0) {
    return { columns: [translate("field"), translate("value")], rows: pairs, keyValue: true };
  }
  const sep = lines[0].includes("|") ? "|" : ",";
  const columns = lines[0].split(sep).map((s) => s.trim());
  return { columns, rows: lines.slice(1).map((line) => line.split(sep).map((s) => s.trim())), keyValue: false };
}

export function TableCard({ code }: { code: string }) {
  const data = parseTable(code);
  if (!data) return <div className="rich-error">{translate("tableInvalid")}</div>;
  if (data.keyValue) {
    return <dl className="kv-card">{data.rows.map(([key, value], i) => <div key={i}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>;
  }
  return <div className="table-scroll"><table className="table-card"><thead><tr>{data.columns.map((c, i) => <th key={i}>{c}</th>)}</tr></thead><tbody>{data.rows.map((row, i) => <tr key={i}>{data.columns.map((_, j) => <td key={j}>{row[j] ?? ""}</td>)}</tr>)}</tbody></table></div>;
}

export function ImageCard({ code }: { code: string }) {
  const [expanded, setExpanded] = useState(false);
  const [fallback, setFallback] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  let data: { base64?: string; uri?: string; caption?: string };
  try { data = JSON.parse(code); } catch { return <div className="rich-error">{translate("imageInvalid")}</div>; }
  const src = data.base64 ? `data:image/png;base64,${data.base64}` : fallback ?? data.uri;
  if (failed || !src || !(src.startsWith("data:image/") || src.startsWith("/local/") || src.startsWith("https://"))) return <div className="rich-error">{translate("imageSourceInvalid")}</div>;
  // content:// 在主 WebView（allowContentAccess=false）里永远加载不出来：
  // 失败时用 chatImageImport 转成 /local/ 只读路由地址重试一次，仍失败显示错误卡。
  const onError = () => {
    if (fallback || failed || !data.uri?.startsWith("content://") || !hasBridge()) { setFailed(true); return; }
    const result = importChatImage(data.uri);
    if (result.ok && result.image.url) setFallback(result.image.url);
    else setFailed(true);
  };
  return <div className="image-card">
    <button type="button" onClick={() => setExpanded(true)} aria-label={translate("enlargeImage")}><img src={src} onError={onError} alt={data.caption ?? translate("editedImage")} /></button>
    {data.caption && <div className="image-caption">{data.caption}</div>}
    {expanded && <ImageLightbox label={translate("imagePreview")} onClose={() => setExpanded(false)}>
      <img src={src} alt={data.caption ?? translate("editedImage")} />
    </ImageLightbox>}
  </div>;
}

/**
 * 相册照片网格的单张缩略图。原生 album_photos 对部分图（HEIC/RAW/云图未下载等）
 * 解不出缩略图，结果里就没有 thumbnailBase64，而 photo.thumb 原生从不产出——
 * 旧版直接 `<img src={undefined}>` 渲染出裂图。现在：无可用 src 时渲染占位块；
 * 加载失败且 uri 是 content:// 时转 /local/ 重试一次，仍失败落回占位块。
 */
function PhotoTile({ photo, index }: { photo: Photo; index: number }) {
  const [src, setSrc] = useState<string | undefined>(
    photo.thumbnailBase64
      ? `data:image/jpeg;base64,${photo.thumbnailBase64}`
      : photo.thumb || undefined,
  );
  const [importTried, setImportTried] = useState(false);
  const name = photo.name || `${translate("photo")} ${index + 1}`;
  const onError = () => {
    if (!importTried && photo.uri && photo.uri.startsWith("content://") && hasBridge()) {
      setImportTried(true);
      const result = importChatImage(photo.uri);
      if (result.ok && result.image.url) { setSrc(result.image.url); return; }
    }
    setSrc(undefined);
  };
  if (!src) {
    return (
      <div className="photo-tile-empty" aria-label={name}>
        <Icon name="image" size={18} />
        <span>{name}</span>
      </div>
    );
  }
  return <button type="button" onClick={async () => {
    const result = await execTool("album_open", { id: photo.id, uri: photo.uri });
    if (result.ok) showSuccess(result.result); else showError(result.result);
  }}><img src={src} onError={onError} alt={name} loading="lazy" /><span>{name}</span></button>;
}

export function PhotosCard({ code }: { code: string }) {
  let photos: Photo[] = [];
  try { const data = JSON.parse(code); photos = Array.isArray(data) ? data : data.photos ?? []; } catch { return <div className="rich-error">{translate("photosInvalid")}</div>; }
  return <div className="photos-grid">{photos.map((photo, i) => <PhotoTile key={photo.id ?? photo.uri ?? i} photo={photo} index={i} />)}</div>;
}

/** Key-value view is the same unified table renderer in its two-column form. */
export function KvCard({ code }: { code: string }) {
  return <TableCard code={code} />;
}

export function GeoCard({ code }: { code: string }) {
  let data: { lat: number; lng: number; label?: string };
  try { data = JSON.parse(code); } catch { return <div className="rich-error">{translate("mapInvalid")}</div>; }
  const { lat, lng, label } = data;
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return <div className="rich-error">{translate("coordinateInvalid")}</div>;
  const bbox = `${lng - .01},${lat - .01},${lng + .01},${lat + .01}`;
  const src = `https://www.openstreetmap.org/export/embed.html?bbox=${encodeURIComponent(bbox)}&marker=${lat}%2C${lng}`;
  return <div className="geo-card"><div>{label ?? `${lat}, ${lng}`}</div><iframe title={label ?? translate("map")} src={src} loading="lazy" /><button type="button" onClick={async () => {
    const result = await execTool("open_uri", { uri: `geo:${lat},${lng}` });
    if (!result.ok) showError(result.result);
  }}>{translate("openMapApp")}</button></div>;
}

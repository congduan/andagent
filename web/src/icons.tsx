/**
 * 统一线性 SVG 图标集（24×24 视框、currentColor 描边）。
 * 所有 UI 图标都走这里，不再使用 emoji —— emoji 在不同系统上字形/尺寸不一致。
 */

const ICON_PATHS = {
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/>',
  alarm: '<circle cx="12" cy="13" r="8"/><path d="M12 9.5V13l2.5 1.5"/><path d="M5 4 3 6"/><path d="m19 4 2 2"/>',
  timer: '<path d="M10 2.5h4"/><circle cx="12" cy="14" r="8"/><path d="M12 14v-4"/>',
  music: '<path d="M9 18V6l10-2v12"/><circle cx="6" cy="18" r="3"/><circle cx="16" cy="16" r="3"/>',
  database: '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>',
  brain: '<path d="M12 5.5a3 3 0 0 0-6 0 3 3 0 0 0-1.8 5.3A3 3 0 0 0 6 16.2a3 3 0 0 0 6 .8z"/><path d="M12 5.5a3 3 0 0 1 6 0 3 3 0 0 1 1.8 5.3A3 3 0 0 1 18 16.2a3 3 0 0 1-6 .8z"/><path d="M12 5.5V21"/>',
  trash: '<path d="M4 7h16"/><path d="M9.5 7V4.8h5V7"/><path d="M6.5 7l1 13h9l1-13"/><path d="M10 11v6M14 11v6"/>',
  wifi: '<path d="M2 8.5a16 16 0 0 1 20 0"/><path d="M5.5 12a11 11 0 0 1 13 0"/><path d="M9 15.5a6 6 0 0 1 6 0"/><circle cx="12" cy="19" r="1"/>',
  antenna: '<circle cx="12" cy="12" r="2"/><path d="M7.5 7.5a6.4 6.4 0 0 0 0 9"/><path d="M16.5 7.5a6.4 6.4 0 0 1 0 9"/><path d="M4.5 4.5a10.6 10.6 0 0 0 0 15"/><path d="M19.5 4.5a10.6 10.6 0 0 1 0 15"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9"/><path d="m16.5 6.5 2 2"/><path d="m13.5 9.5 2 2"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 0 18a15 15 0 0 1 0-18"/>',
  smartphone: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>',
  'hard-drive': '<rect x="3" y="3.5" width="18" height="17" rx="2.5"/><path d="M7 3.5v6h9v-6"/><rect x="7" y="14" width="10" height="6.5" rx="1"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="m21 16-5-5-9 9"/>',
  camera: '<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M9 7l1.5-2.5h3L15 7"/><circle cx="12" cy="13.5" r="3.5"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 10h18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  battery: '<rect x="2.5" y="8" width="16" height="8" rx="2"/><path d="M21 11v2"/><path d="M6 11v2M9.5 11v2"/>',
  clipboard: '<rect x="6" y="4" width="12" height="17" rx="2"/><path d="M9.5 4V2.8h5V4"/><path d="M9 10h6M9 14h6"/>',
  bell: '<path d="M6 9.5a6 6 0 1 1 12 0c0 4.5 1.8 5.8 1.8 5.8H4.2S6 14 6 9.5z"/><path d="M10 19a2 2 0 0 0 4 0"/>',
  package: '<path d="m12 3 9 4.5v9L12 21l-9-4.5v-9z"/><path d="m3 7.5 9 4.5 9-4.5"/><path d="M12 12v9"/>',
  bluetooth: '<path d="m7 7 10 10-5 4V3l5 4L7 17"/>',
  keyboard: '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7.5 14h9"/>',
  'file-code': '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="m10.5 12-2 2.5 2 2.5M14 12l2 2.5-2 2.5"/>',
  'file-text': '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/>',
  play: '<path d="M8 5.5v13l11-6.5z"/>',
  check: '<path d="m4.5 12.5 5 5 10-11"/>',
  wrench: '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>',
  lock: '<rect x="4.5" y="10" width="15" height="10.5" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
  monitor: '<rect x="2.5" y="4" width="19" height="12.5" rx="2"/><path d="M8 20.5h8M12 16.5v4"/>',
  plug: '<path d="M9 3v6M15 3v6"/><path d="M6.5 9h11v1.8a5.5 5.5 0 0 1-11 0z"/><path d="M12 16.3V21"/>',
  download: '<path d="M12 3v12"/><path d="m7 11 5 5 5-5"/><path d="M4 20h16"/>',
  upload: '<path d="M12 20V8"/><path d="m7 12 5-5 5 5"/><path d="M4 3.5h16"/>',
  folder: '<path d="M3 7.5A2 2 0 0 1 5 5.5h3.6a2 2 0 0 1 1.6.8l.9 1.2H19a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  video: '<rect x="2.5" y="6" width="13" height="12" rx="2"/><path d="m15.5 10.5 5-3v9l-5-3z"/>',
  pointer: '<path d="M9 11V6a1.5 1.5 0 0 1 3 0v5"/><path d="M12 11V4.5a1.5 1.5 0 0 1 3 0V11"/><path d="M15 11.5V7a1.5 1.5 0 0 1 3 0v8a5.5 5.5 0 0 1-5.5 5.5H11A5.5 5.5 0 0 1 5.5 15v-3a1.5 1.5 0 0 1 3 0v1"/>',
  bug: '<path d="M8 8.5a4 4 0 0 1 8 0v5a4 4 0 0 1-8 0z"/><path d="M8 11H4.5M19.5 11H16M8.5 6.5 6 4M15.5 6.5 18 4M8.5 14.5 6 17M15.5 14.5 18 17M12 17.5V21"/>',
  palette: '<path d="M12 3a9 9 0 1 0 0 18c1.4 0 2-1 2-2s-1-1.7-1-2.6c0-1 .8-1.4 2-1.4h1.5A4.5 4.5 0 0 0 21 10.5C21 6.4 16.9 3 12 3z"/><circle cx="7.5" cy="10" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="14" cy="7" r="1"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>',
  puzzle: '<path d="M9 4.5a2 2 0 0 1 4 0V6h3.5a1 1 0 0 1 1 1v3h-1.5a2 2 0 0 0 0 4H17.5v3.5a1 1 0 0 1-1 1H13v-1.5a2 2 0 0 0-4 0V18.5H5.5a1 1 0 0 1-1-1V14H6a2 2 0 0 0 0-4H4.5V6.5a1 1 0 0 1 1-1H9z"/>',
  link: '<path d="M10 13a4 4 0 0 0 6 .5l2-2a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 11a4 4 0 0 0-6-.5l-2 2A4 4 0 0 0 11.7 18l1-1"/>',
  phone: '<path d="M6.5 3.5h3l1.5 4-2 1.5a12 12 0 0 0 6 6l1.5-2 4 1.5v3a2 2 0 0 1-2 2A16.5 16.5 0 0 1 4.5 5.5a2 2 0 0 1 2-2z"/>',
  'map-pin': '<path d="M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  message: '<path d="M20.5 12a8.5 8.5 0 0 1-12.3 7.6L4 21l1.4-4.2A8.5 8.5 0 1 1 20.5 12z"/>',
  mic: '<rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0"/><path d="M12 17.5V21M8.5 21h7"/>',
  compass: '<circle cx="12" cy="12" r="9"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7"/><path d="M20 4v5h-5"/>',
  repeat: '<path d="M17 3l3 3-3 3"/><path d="M20 6H8a4 4 0 0 0-4 4v1"/><path d="M7 21l-3-3 3-3"/><path d="M4 18h12a4 4 0 0 0 4-4v-1"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  film: '<rect x="2.5" y="4" width="19" height="16" rx="2"/><path d="M7 4v16M17 4v16M2.5 12h19"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.8-3.8"/>',
  pencil: '<path d="M4 20h4L20 8a2.8 2.8 0 0 0-4-4L4 16z"/><path d="m14 6 4 4"/>',
  heart: '<path d="M12 20s-7-4.4-7-9.5A4 4 0 0 1 12 7a4 4 0 0 1 7 3.5C19 15.6 12 20 12 20z"/>',
  activity: '<path d="M3 12h3.5l2.5-6 4 12 2.5-6H21"/>',
  thermometer: '<path d="M14 14.5V5a2 2 0 1 0-4 0v9.5a4 4 0 1 0 4 0z"/><path d="M12 9v6"/>',
  vibrate: '<rect x="8.5" y="4" width="7" height="16" rx="2"/><path d="M4.5 9v6M2 11v2M19.5 9v6M22 11v2"/>',
  volume: '<path d="M4 9.5h3L11 6v12l-4-3.5H4z"/><path d="M15 9a4 4 0 0 1 0 6"/><path d="M17.5 6.5a7.5 7.5 0 0 1 0 11"/>',
  headphones: '<path d="M4 14v-2a8 8 0 0 1 16 0v2"/><rect x="2.5" y="13" width="4.5" height="7" rx="2"/><rect x="17" y="13" width="4.5" height="7" rx="2"/>',
  settings: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4M5.2 5.2l2.8 2.8M16 16l2.8 2.8M18.8 5.2 16 8M8 16l-2.8 2.8"/>',
  maximize: '<path d="M4 9.5V5.5A1.5 1.5 0 0 1 5.5 4H9.5"/><path d="M14.5 4h4A1.5 1.5 0 0 1 20 5.5v4"/><path d="M20 14.5v4a1.5 1.5 0 0 1-1.5 1.5h-4"/><path d="M9.5 20h-4A1.5 1.5 0 0 1 4 18.5v-4"/>',
  minimize: '<path d="M9.5 4v4a1.5 1.5 0 0 1-1.5 1.5H4"/><path d="M20 9.5h-4A1.5 1.5 0 0 1 14.5 8V4"/><path d="M14.5 20v-4a1.5 1.5 0 0 1 1.5-1.5h4"/><path d="M4 14.5h4A1.5 1.5 0 0 1 9.5 16v4"/>',
  x: '<path d="m6 6 12 12M18 6 6 18"/>',
  'arrow-left': '<path d="M19 12H5"/><path d="m11 6-6 6 6 6"/>',
  'arrow-up-right': '<path d="M7 17 17 7"/><path d="M8 7h9v9"/>',
  'arrow-up': '<path d="M12 20V5"/><path d="m6 11 6-6 6 6"/>',
  'arrow-down': '<path d="M12 4v15"/><path d="m6 13 6 6 6-6"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M15 9V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h4"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M5 5l1.8 1.8M17.2 17.2 19 19M19 5l-1.8 1.8M6.8 17.2 5 19"/>',
  alert: '<path d="M12 3.5 2.5 20h19z"/><path d="M12 9.5v4.5"/><path d="M12 17h.01"/>',
  zap: '<path d="M13 2 4 14h6l-1 8 9-12h-6z"/>',
  toolbox: '<rect x="3" y="8" width="18" height="12" rx="2"/><path d="M9 8V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/><path d="M3 13h18"/>',
  sparkles: '<path d="m12 3 1.8 4.7L18.5 9.5 13.8 11.3 12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"/><path d="m18 15.5.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
  ruler: '<path d="M15.5 3.5 20.5 8.5 8.5 20.5 3.5 15.5z"/><path d="m8 11 2 2M11 8l2 2M5 14l2 2"/>',
  notebook: '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 3v18"/><path d="M12.5 8h3M12.5 12h3"/>',
  'bar-chart': '<path d="M5 20v-7M11 20V5M17 20v-9"/><path d="M3.5 21h17"/>',
  'scan-text': '<path d="M4 8.5V6a2 2 0 0 1 2-2h2.5"/><path d="M15.5 4H18a2 2 0 0 1 2 2v2.5"/><path d="M20 15.5V18a2 2 0 0 1-2 2h-2.5"/><path d="M8.5 20H6a2 2 0 0 1-2-2v-2.5"/><path d="M8.5 11.5h7"/><path d="M8.5 15h4.5"/>',
  'chevron-right': '<path d="m9 5 7 7-7 7"/>',
} as const;

export type IconName = keyof typeof ICON_PATHS;

export interface IconProps {
  name: IconName;
  /** 边长（px），默认随容器字号走 16。 */
  size?: number;
  className?: string;
  strokeWidth?: number;
}

/** React 用：内联 SVG，颜色跟随 currentColor。 */
export function Icon({ name, size = 16, className, strokeWidth = 1.8 }: IconProps) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      dangerouslySetInnerHTML={{ __html: ICON_PATHS[name] }}
    />
  );
}

/** 非 React 场景（toast 等直接操作 DOM 的地方）用字符串形态。 */
export function iconMarkup(name: IconName, size = 16, strokeWidth = 2): string {
  return (
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor"` +
    ` stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round"` +
    ` aria-hidden="true" focusable="false">${ICON_PATHS[name]}</svg>`
  );
}

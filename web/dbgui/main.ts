import initSqlJs from 'sql.js';
import { Grid, html } from 'gridjs';
import './style.css';

declare global { interface Window { DbGuiBridge: { info(): string; readDbBase64(name: string): string; sqlExec(name: string, sql: string, readOnly: boolean): string; close(): void } } }
type Result = { columns: string[]; rows: unknown[][]; truncated: boolean; error?: string };
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const bridge = window.DbGuiBridge;
let name = '';
let database: InstanceType<Awaited<ReturnType<typeof initSqlJs>>['Database']> | null = null;
let tables: string[] = [];
const tableCounts = new Map<string, string>();
let selected = '';
let page = 0;
let hasMore = false;
let currentRows: unknown[][] = [];
let grid: Grid | null = null;
// Mobile gets fewer rows per page so rows stay tall/tappable without long scrolls.
const pageSize = window.matchMedia("(max-width: 719px)").matches ? 15 : 50;
function showError(message: string) { $('status').textContent = message; }
function parseResult(raw: string): Result { const value = JSON.parse(raw); if (value.error) throw new Error(value.error); return value; }
function read(sql: string): Result {
  if (database) { const stmt = database.prepare(sql); try { const columns = stmt.getColumnNames(); const rows: unknown[][] = []; while (rows.length < pageSize + 1 && stmt.step()) rows.push(stmt.get()); return { columns, rows: rows.slice(0, pageSize), truncated: rows.length > pageSize }; } finally { stmt.free(); } }
  return parseResult(bridge.sqlExec(name, sql, true));
}
function tableQuery(table: string, offset: number) { return `SELECT * FROM "${table.replaceAll('"', '""')}" LIMIT ${pageSize + 1} OFFSET ${offset}`; }
function escapeHtml(value: string) { return value.replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!); }
function renderValue(value: unknown) {
  if (value == null) return html('<span class="cell-null">NULL</span>');
  const kind = typeof value === 'number' ? 'cell-number' : 'cell-text';
  return html(`<span class="${kind}">${escapeHtml(String(value))}</span>`);
}
function renderGrid(result: Result) {
  currentRows = result.rows.slice(0, pageSize);
  hasMore = result.truncated || result.rows.length > pageSize;
  const columns = result.columns.length ? result.columns : ['结果'];
  const data = currentRows.map(row => row.map(renderValue));
  if (grid) { grid.destroy(); $('grid').replaceChildren(); }
  grid = new Grid({ columns, data, sort: false, search: false, pagination: false, fixedHeader: true, width: '100%', height: '45dvh' });
  grid.render($('grid'));
  $('page').textContent = `第 ${page + 1} 页`;
  ($<HTMLButtonElement>('prev')).disabled = page === 0 || !selected;
  ($<HTMLButtonElement>('next')).disabled = !hasMore || !selected;
  showError(`${currentRows.length} 行${hasMore ? '，还有更多' : ''}`);
}
function renderTables() {
  $('tables').replaceChildren(...tables.map(table => {
    const button = document.createElement('button'); button.className = `table${table === selected ? ' active' : ''}`;
    button.type = 'button'; button.title = table;
    const icon = document.createElement('span'); icon.className = 'table-icon'; icon.textContent = '▦'; icon.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span'); label.className = 'table-name'; label.textContent = table;
    const badge = document.createElement('span'); badge.className = 'table-count'; badge.textContent = tableCounts.get(table) ?? '…';
    button.append(icon, label, badge);
    button.onclick = () => { selected = table; page = 0; showTable(); }; return button;
  }));
}
function showTable() { try { renderTables(); $('current').textContent = selected; renderGrid(read(tableQuery(selected, page * pageSize))); } catch (e) { showError(String(e)); } }
$('prev').onclick = () => { if (page > 0) { page--; showTable(); } };
$('next').onclick = () => { if (hasMore) { page++; showTable(); } };
$('query').addEventListener('submit', event => { event.preventDefault(); try { const sql = ($<HTMLTextAreaElement>('sql')).value.trim(); if (!/^(select|with|explain)\b/i.test(sql)) throw new Error('只允许 SELECT、WITH 或 EXPLAIN 查询'); selected = ''; page = 0; renderTables(); $('current').textContent = 'SQL 查询结果'; renderGrid(parseResult(bridge.sqlExec(name, sql, true))); } catch (e) { showError(String(e)); } });
const sqlInput = $<HTMLTextAreaElement>('sql');
const sqlHighlight = $('sql-highlight');
function highlightSql() {
  const escaped = escapeHtml(sqlInput.value || ' ');
  sqlHighlight.innerHTML = escaped.replace(/\b(SELECT|FROM|WHERE|WITH|AS|JOIN|LEFT|RIGHT|INNER|ON|ORDER|BY|GROUP|LIMIT|OFFSET|AND|OR|NOT|NULL|EXPLAIN|ASC|DESC)\b/gi, '<span class="sql-keyword">$1</span>') + '\n';
}
sqlInput.addEventListener('input', highlightSql);
sqlInput.addEventListener('scroll', () => { sqlHighlight.scrollTop = sqlInput.scrollTop; sqlHighlight.scrollLeft = sqlInput.scrollLeft; });
sqlInput.addEventListener('keydown', event => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); $<HTMLFormElement>('query').requestSubmit(); } });
highlightSql();
$('grid').addEventListener('click', event => { const cell = (event.target as Element).closest('td.gridjs-td'); const row = cell?.parentElement; if (!cell || !row) return; const rowIndex = Array.from(row.parentElement?.children || []).indexOf(row); const colIndex = Array.from(row.children).indexOf(cell); const value = currentRows[rowIndex]?.[colIndex]; $('detail-text').textContent = value == null ? 'NULL' : String(value); ($<HTMLDialogElement>('detail')).showModal(); });
$('detail-close').onclick = () => ($<HTMLDialogElement>('detail')).close();
function release() { database?.close(); database = null; grid?.destroy(); grid = null; currentRows = []; }
window.addEventListener('pagehide', release);
$('back').onclick = () => { release(); bridge.close(); };
async function start() {
  try {
    const info = JSON.parse(bridge.info()); if (info.error) throw new Error(info.error); name = info.name;
    $('title').textContent = `数据库：${name}`;
    if (info.mode === 'wasm') {
      const SQL = await initSqlJs({ locateFile: () => './sql-wasm.wasm' });
      const encoded = bridge.readDbBase64(name); if (encoded.startsWith('ERROR:')) throw new Error(encoded);
      const binary = atob(encoded); const bytes = Uint8Array.from(binary, ch => ch.charCodeAt(0));
      database = new SQL.Database(bytes);
      $('mode').textContent = 'sql.js 快照 · 只读';
    } else $('mode').textContent = 'Android 分页 · 只读';
    const result = read("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
    tables = result.rows.map(row => String(row[0])); renderTables();
    for (const table of tables) {
      try { tableCounts.set(table, Number(read(`SELECT COUNT(*) FROM "${table.replaceAll('"', '""')}"`).rows[0]?.[0]).toLocaleString()); }
      catch { tableCounts.set(table, '—'); }
    }
    renderTables();
    if (tables.length) { selected = tables[0]; showTable(); } else showError('数据库中没有表');
  } catch (e) { showError(String(e)); }
}
void start();

import { useEffect, useState } from "react";
import { cancelSshTransfer } from "./bridge";
import { useI18n } from "./i18n";

export type TransferEvent = {
  type: "ssh.transfer.progress" | "ssh.transfer.done";
  key: string;
  name: string;
  direction: "download" | "upload";
  percent?: number;
  bytes?: number;
  total?: number;
  ok?: boolean;
  savedPath?: string;
};

export type TransferRow = TransferEvent & { at: number; speed: number; cancelling?: boolean };

export function updateTransfer(rows: TransferRow[], event: TransferEvent, at: number): TransferRow[] {
  if (!event.key || !event.name || !["download", "upload"].includes(event.direction)) return rows;
  const old = rows.find(row => row.key === event.key);
  const bytes = event.type === "ssh.transfer.done" ? old?.bytes ?? 0 : Math.max(0, Number(event.bytes) || 0);
  const elapsed = old ? (at - old.at) / 1000 : 0;
  const speed = elapsed > 0 && bytes > (old?.bytes ?? 0)
    ? (bytes - (old?.bytes ?? 0)) / elapsed : old?.speed ?? 0;
  const row: TransferRow = {
    ...old, ...event, bytes,
    total: event.type === "ssh.transfer.done" ? old?.total ?? 0 : Math.max(0, Number(event.total) || 0),
    percent: event.type === "ssh.transfer.done" ? event.ok ? 100 : old?.percent ?? 0 : Math.min(100, Math.max(0, Number(event.percent) || 0)),
    at, speed, cancelling: event.type === "ssh.transfer.done" ? false : old?.cancelling,
  };
  return [row, ...rows.filter(item => item.key !== event.key)].slice(0, 3);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}

export function SshTransferHost() {
  const { t } = useI18n();
  const [rows, setRows] = useState<TransferRow[]>([]);
  useEffect(() => {
    const receive = (raw: Event) => {
      const event = (raw as CustomEvent<TransferEvent>).detail;
      if (event?.type === "ssh.transfer.progress" || event?.type === "ssh.transfer.done") {
        // done 事件也刷新 at：自动淡出计时以终态到达时刻为起点。
        setRows(previous => updateTransfer(previous, event, event.type === "ssh.transfer.done" ? Date.now() : Date.now()));
      }
    };
    window.addEventListener("andagent:ssh-transfer", receive);
    // 终态卡片（完成/失败）自动淡出：成功 4s、失败 8s（给用户看清错误），或手动 ✕ 关闭。
    const sweep = window.setInterval(() => {
      setRows(previous => {
        const now = Date.now();
        const next = previous.filter(row => {
          if (row.type !== "ssh.transfer.done") return true;
          const keepMs = row.ok ? 4000 : 8000;
          return now - row.at < keepMs;
        });
        return next.length === previous.length ? previous : next;
      });
    }, 1000);
    return () => {
      window.removeEventListener("andagent:ssh-transfer", receive);
      window.clearInterval(sweep);
    };
  }, []);
  if (!rows.length) return null;
  return <div className="ssh-transfer-host" aria-live="polite">
    {rows.map(row => {
      const done = row.type === "ssh.transfer.done";
      const failed = done && !row.ok;
      return <div className={`ssh-transfer-card${failed ? " failed" : ""}`} key={row.key}>
        <div className="ssh-transfer-heading">
          <span aria-hidden="true">{row.direction === "download" ? "⬇" : "⬆"}</span>
          <strong title={row.name}>{row.name}</strong>
          <b>{row.percent}%</b>
        </div>
        <div className="ssh-transfer-track" role="progressbar" aria-label={row.name} aria-valuenow={row.percent} aria-valuemin={0} aria-valuemax={100}>
          <span style={{ width: `${row.percent}%` }} />
        </div>
        <div className="ssh-transfer-detail">
          <span>{formatBytes(row.bytes ?? 0)} / {row.total ? formatBytes(row.total) : "—"}</span>
          {!done && <span>{(row.speed / 1_048_576).toFixed(1)} MB/s</span>}
          {failed && <span className="ssh-transfer-failed-label">{t("sshTransferFailed")}</span>}
          {!done && <button type="button" disabled={row.cancelling} onClick={() => {
            cancelSshTransfer(row.key);
            setRows(previous => previous.map(item => item.key === row.key ? { ...item, cancelling: true } : item));
          }}>{t(row.cancelling ? "sshTransferCancelling" : "sshTransferCancel")}</button>}
          {done && <button type="button" className="ssh-transfer-dismiss" aria-label={t("close")} onClick={() => {
            setRows(previous => previous.filter(item => item.key !== row.key));
          }}>✕</button>}
        </div>
        {done && row.savedPath && <div className="ssh-transfer-path">
          <span title={row.savedPath}>{row.savedPath}</span>
          <button type="button" onClick={() => void navigator.clipboard.writeText(row.savedPath!)}>{t("sshTransferCopyPath")}</button>
        </div>}
      </div>;
    })}
  </div>;
}

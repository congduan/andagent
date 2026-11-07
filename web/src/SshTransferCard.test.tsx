import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { SshTransferHost, updateTransfer } from "./SshTransferCard";

describe("SSH transfer card", () => {
  it("aggregates repeated progress, computes speed, and keeps the done path", () => {
    const first = updateTransfer([], { type: "ssh.transfer.progress", key: "s\n/a", name: "a", direction: "download", bytes: 1_048_576, total: 4_194_304, percent: 25 }, 1000);
    const next = updateTransfer(first, { type: "ssh.transfer.progress", key: "s\n/a", name: "a", direction: "download", bytes: 2_097_152, total: 4_194_304, percent: 50 }, 2000);
    expect(next).toHaveLength(1);
    expect(next[0].speed).toBe(1_048_576);
    const done = updateTransfer(next, { type: "ssh.transfer.done", key: "s\n/a", name: "a", direction: "download", ok: true, savedPath: "/sdcard/a" }, 3000);
    expect(done[0]).toMatchObject({ percent: 100, savedPath: "/sdcard/a", bytes: 2_097_152 });
  });

  it("renders progress and a failed terminal state", () => {
    render(<SshTransferHost />);
    fireEvent(window, new CustomEvent("andagent:ssh-transfer", { detail: { type: "ssh.transfer.progress", key: "s\n/a", name: "a.zip", direction: "download", bytes: 1_048_576, total: 2_097_152, percent: 50 } }));
    expect(screen.getByRole("progressbar").getAttribute("aria-valuenow")).toBe("50");
    expect(screen.getByText("1.0 MB / 2.0 MB")).toBeTruthy();
    fireEvent(window, new CustomEvent("andagent:ssh-transfer", { detail: { type: "ssh.transfer.done", key: "s\n/a", name: "a.zip", direction: "download", ok: false } }));
    expect(screen.getByRole("progressbar").getAttribute("aria-valuenow")).toBe("50");
    expect(screen.queryByRole("button")).toBeNull();
  });
});

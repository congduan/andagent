import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ResourceHubPanel } from "./ResourceHubPanel";

const list = vi.hoisted(() => vi.fn());
const close = vi.hoisted(() => vi.fn());
const confirm = vi.hoisted(() => vi.fn());
vi.mock("./bridge", () => ({ resourceHubList: list, resourceHubClose: close }));
vi.mock("./ConfirmDialog", () => ({ appConfirm: confirm }));
vi.mock("./toast", () => ({ showResult: vi.fn() }));

beforeEach(() => { list.mockReset(); close.mockReset(); confirm.mockReset().mockResolvedValue(true); });

it("renders an empty state", () => {
  list.mockReturnValue([]);
  render(<ResourceHubPanel onClose={() => {}} />);
  expect(screen.getByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

it("groups resources and closes only actionable rows after confirmation", async () => {
  list.mockReturnValue([
    { kind: "ssh", id: "s1", title: "server", subtitle: "host:22", aliveSince: Date.now() - 60000, canClose: true },
    { kind: "cron", id: "c1", title: "backup", subtitle: "daily", canClose: true },
    { kind: "saf", id: "tree", title: "readonly", subtitle: "tree", canClose: false },
  ]);
  close.mockReturnValue("OK");
  render(<ResourceHubPanel onClose={() => {}} />);
  expect(screen.getByText("server")).toBeTruthy();
  expect(screen.getByText("backup")).toBeTruthy();
  expect(screen.getByText(/暂停|Pause/)).toBeTruthy();
  expect(screen.getAllByText(/断开 \/ 停止|Disconnect \/ stop/)).toHaveLength(1);
  fireEvent.click(screen.getByText(/断开 \/ 停止|Disconnect \/ stop/));
  await waitFor(() => expect(close).toHaveBeenCalledWith("ssh", "s1"));
  expect(confirm).toHaveBeenCalledOnce();
});

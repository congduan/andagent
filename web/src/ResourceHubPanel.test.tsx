import { render, screen, fireEvent, waitFor, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ResourceHubPanel } from "./ResourceHubPanel";

const list = vi.hoisted(() => vi.fn());
const close = vi.hoisted(() => vi.fn());
const confirm = vi.hoisted(() => vi.fn());
vi.mock("./bridge", () => ({ resourceHubList: list, resourceHubClose: close }));
vi.mock("./ConfirmDialog", () => ({ appConfirm: confirm }));
vi.mock("./toast", () => ({ showResult: vi.fn() }));

beforeEach(() => {
  list.mockReset().mockResolvedValue([]);
  close.mockReset().mockResolvedValue("OK");
  confirm.mockReset().mockResolvedValue(true);
});
afterEach(() => cleanup());

it("renders an empty state", async () => {
  list.mockResolvedValue([]);
  render(<ResourceHubPanel onClose={() => {}} />);
  expect(await screen.findByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

// R110-review 必修项①：async 通道初次渲染即先显示空态，不等列表返回、不报错。
it("resourceHubList 空面板先渲染空态", () => {
  // 挂起的 Promise：模拟主帧凭证轮询 / 桥调用尚未返回。
  list.mockReturnValue(new Promise(() => {}));
  render(<ResourceHubPanel onClose={() => {}} />);
  // 初始 useState([]) 立即渲染空态，不阻塞、不抛错。
  expect(screen.getByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

it("groups resources and closes only actionable rows after confirmation", async () => {
  list.mockResolvedValue([
    { kind: "ssh", id: "s1", title: "server", subtitle: "host:22", aliveSince: Date.now() - 60000, canClose: true },
    { kind: "cron", id: "c1", title: "backup", subtitle: "daily", canClose: true },
    { kind: "saf", id: "tree", title: "readonly", subtitle: "tree", canClose: false },
  ]);
  close.mockResolvedValue("OK");
  render(<ResourceHubPanel onClose={() => {}} />);
  expect(await screen.findByText("server")).toBeTruthy();
  expect(screen.getByText("backup")).toBeTruthy();
  expect(screen.getByText(/暂停|Pause/)).toBeTruthy();
  expect(screen.getAllByText(/断开 \/ 停止|Disconnect \/ stop/)).toHaveLength(1);
  fireEvent.click(screen.getByText(/断开 \/ 停止|Disconnect \/ stop/));
  await waitFor(() => expect(close).toHaveBeenCalledWith("ssh", "s1"));
  expect(confirm).toHaveBeenCalledOnce();
});

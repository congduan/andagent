import { render, screen, fireEvent, waitFor, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ResourceHubPanel } from "./ResourceHubPanel";

const snapshot = vi.hoisted(() => vi.fn());
const close = vi.hoisted(() => vi.fn());
const confirm = vi.hoisted(() => vi.fn());
vi.mock("./bridge", () => ({ resourceHubSnapshot: snapshot, resourceHubClose: close }));
vi.mock("./ConfirmDialog", () => ({ appConfirm: confirm }));
vi.mock("./toast", () => ({ showResult: vi.fn() }));

beforeEach(() => {
  snapshot.mockReset().mockResolvedValue({ items: [], failed: false });
  close.mockReset().mockResolvedValue("OK");
  confirm.mockReset().mockResolvedValue(true);
});
afterEach(() => cleanup());

it("renders an empty state", async () => {
  snapshot.mockResolvedValue({ items: [], failed: false });
  render(<ResourceHubPanel anchor={null} onClose={() => {}} />);
  expect(await screen.findByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

// R110-review 必修项①：async 通道初次渲染即先显示空态，不等列表返回、不报错。
it("resourceHubSnapshot 空面板先渲染空态", () => {
  // 挂起的 Promise：模拟主帧凭证轮询 / 桥调用尚未返回。
  snapshot.mockReturnValue(new Promise(() => {}));
  render(<ResourceHubPanel anchor={null} onClose={() => {}} />);
  // 初始 useState([]) 立即渲染空态，不阻塞、不抛错。
  expect(screen.getByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

// 读取失败不能伪装成“没有资源”：显示错误态 + 重试，而不是空态。
it("读取失败显示错误态与重试，而不是空态", async () => {
  snapshot.mockResolvedValue({ items: [], failed: true });
  render(<ResourceHubPanel anchor={null} onClose={() => {}} />);
  expect(await screen.findByText(/资源列表读取失败|Could not load resources/)).toBeTruthy();
  expect(screen.queryByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeNull();
  snapshot.mockResolvedValue({ items: [], failed: false });
  fireEvent.click(screen.getByText(/重试|Retry/));
  await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
  expect(await screen.findByText(/当前无存活的连接与任务|No live connections or tasks/)).toBeTruthy();
});

it("groups resources and closes only actionable rows after confirmation", async () => {
  snapshot.mockResolvedValue({
    items: [
      { kind: "ssh", id: "s1", title: "server", subtitle: "host:22", aliveSince: Date.now() - 60000, canClose: true },
      { kind: "cron", id: "c1", title: "backup", subtitle: "daily", aliveSince: null, canClose: true },
      { kind: "saf", id: "tree", title: "readonly", subtitle: "tree", aliveSince: null, canClose: false },
    ],
    failed: false,
  });
  close.mockResolvedValue("OK");
  render(<ResourceHubPanel anchor={null} onClose={() => {}} />);
  expect(await screen.findByText("server")).toBeTruthy();
  expect(screen.getByText("backup")).toBeTruthy();
  expect(screen.getByText(/暂停|Pause/)).toBeTruthy();
  expect(screen.getAllByText(/断开 \/ 停止|Disconnect \/ stop/)).toHaveLength(1);
  fireEvent.click(screen.getByText(/断开 \/ 停止|Disconnect \/ stop/));
  await waitFor(() => expect(close).toHaveBeenCalledWith("ssh", "s1"));
  expect(confirm).toHaveBeenCalledOnce();
});

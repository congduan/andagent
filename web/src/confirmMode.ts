export type ConfirmMode = "off" | "destructive" | "all";

const KEY = "andagent.confirm-mode";

export function loadConfirmMode(): ConfirmMode {
  try {
    const value = localStorage.getItem(KEY);
    return value === "destructive" || value === "all" ? value : "off";
  } catch {
    return "off";
  }
}

export function setConfirmMode(mode: ConfirmMode): void {
  localStorage.setItem(KEY, mode);
}

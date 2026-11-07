/**
 * Token usage formatting and aggregation (R40).
 * Pi AssistantMessage.usage 携带 input/output/cacheRead/cacheWrite/totalTokens；
 * 这里只聚合计费无关的三项（不做费用换算——价格表维护超出范围）。
 */

export interface UsageSum {
  input: number;
  output: number;
  totalTokens: number;
}

export function emptyUsage(): UsageSum {
  return { input: 0, output: 0, totalTokens: 0 };
}

type UsageLike =
  | { input?: number; output?: number; cacheRead?: number; cacheWrite?: number; totalTokens?: number }
  | null
  | undefined;

/** In-place accumulate; missing fields count as 0 (aborted/error messages carry zeros). */
export function addUsage(acc: UsageSum, usage: UsageLike): void {
  acc.input += usage?.input ?? 0;
  acc.output += usage?.output ?? 0;
  acc.totalTokens += usage?.totalTokens ?? 0;
}

/**
 * Sum usage over a PiMessage[] transcript (assistant messages only).
 * Aborted messages are skipped: pi 的 aborted 分支塞的是 ZERO_USAGE，无消耗可回报。
 */
export function sumMessageUsage(messages: readonly unknown[]): UsageSum {
  const acc = emptyUsage();
  for (const message of messages) {
    const msg = message as {
      role?: string;
      stopReason?: string;
      usage?: UsageLike;
    };
    if (msg?.role !== "assistant" || msg.stopReason === "aborted") continue;
    addUsage(acc, msg.usage);
  }
  return acc;
}

/** 会话用量明细（「更多」菜单里展示）：比 UsageSum 多出缓存读/写两项。 */
export interface UsageBreakdown {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  totalTokens: number;
  /** 参与计费的 assistant 轮次（含工具轮）。 */
  turns: number;
}

export function emptyBreakdown(): UsageBreakdown {
  return { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, turns: 0 };
}

/** 逐条累加缓存读/写与轮次；统计口径与 sumMessageUsage 一致（跳过 aborted）。 */
export function sumUsageBreakdown(messages: readonly unknown[]): UsageBreakdown {
  const acc = emptyBreakdown();
  for (const message of messages) {
    const msg = message as { role?: string; stopReason?: string; usage?: UsageLike };
    if (msg?.role !== "assistant" || msg.stopReason === "aborted") continue;
    acc.input += msg.usage?.input ?? 0;
    acc.output += msg.usage?.output ?? 0;
    acc.cacheRead += msg.usage?.cacheRead ?? 0;
    acc.cacheWrite += msg.usage?.cacheWrite ?? 0;
    acc.totalTokens += msg.usage?.totalTokens ?? 0;
    acc.turns += 1;
  }
  return acc;
}

/** <1000 原样，否则 k 缩写保留 1 位小数（842 → "842"，45600 → "45.6k"）。 */
export function fmtTokens(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return "0";
  if (n < 1000) return String(Math.round(n));
  return `${(n / 1000).toFixed(1)}k`;
}

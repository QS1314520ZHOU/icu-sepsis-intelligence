/** Bounded retries for transient transport/service failures only. */
export function decisionRetryDelay(attempt: number): number | null {
  return [30000, 60000, 120000][attempt] ?? null;
}
export function transientDecisionStatus(status: string): boolean {
  return status === 'busy' || status === 'unavailable';
}
export function transientDecisionError(error: unknown): boolean {
  const status = (error as {status?: unknown} | null)?.status;
  return typeof status === 'number' && (status === 0 || status === 408 || status === 429 || (status >= 500 && status <= 599));
}

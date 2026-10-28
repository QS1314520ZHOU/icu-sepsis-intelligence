export const namespace = 'icu.sepsis';
export interface HostMessage {
  namespace: string;
  version: 1;
  type: 'SET_CONTEXT' | 'CLEAR_CONTEXT';
  channel: string;
  requestId: string;
  payload?: { code: string; patientId: string; encounterId: string };
}
export function trustedMessage(
  event: { origin: string; source: unknown; data: unknown },
  parent: unknown,
  origins: readonly string[],
  channel: string,
): HostMessage | null {
  if (
    event.source !== parent ||
    !origins.includes(event.origin) ||
    !event.data ||
    typeof event.data !== 'object'
  )
    return null;
  const m = event.data as Record<string, unknown>;
  if (
    m.namespace !== namespace ||
    m.version !== 1 ||
    m.channel !== channel ||
    typeof m.requestId !== 'string' ||
    !/^[A-Za-z0-9_-]{8,100}$/.test(m.requestId)
  )
    return null;
  if (m.type === 'CLEAR_CONTEXT') return m as unknown as HostMessage;
  if (m.type !== 'SET_CONTEXT' || !m.payload || typeof m.payload !== 'object') return null;
  const p = m.payload as Record<string, unknown>;
  if (Object.keys(p).some((k) => !['code', 'patientId', 'encounterId'].includes(k))) return null;
  for (const k of ['code', 'patientId', 'encounterId'])
    if (typeof p[k] !== 'string' || !(p[k] as string).length || (p[k] as string).length > 128)
      return null;
  return m as unknown as HostMessage;
}

/** Preserve source precision while editing an instant in the browser local timezone. */
export function localEvidenceTime(instant?: string): string {
  if (!instant) return '';
  const date = new Date(instant);
  if (!Number.isFinite(date.getTime())) return '';
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 23);
}

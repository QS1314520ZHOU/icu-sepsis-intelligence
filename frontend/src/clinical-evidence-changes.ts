/** In-memory revisions only; no note bodies or browser persistence. */
export class ClinicalEvidenceChanges {
  private sequence = 0;
  private revisions = new Map<string, number>();
  private noteSignatures = new Map<string, string>();
  private listeners = new Set<(patientId: string, encounterId: string) => void>();
  private key(patientId: string, encounterId: string) { return JSON.stringify([patientId, encounterId]); }
  revision(patientId: string, encounterId: string) { return this.revisions.get(this.key(patientId, encounterId)) || 0; }
  subscribe(listener: (patientId: string, encounterId: string) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  notify(patientId: string, encounterId: string) {
    if (!patientId || !encounterId) return;
    const key = this.key(patientId, encounterId);
    if (this.revisions.size >= 100 && !this.revisions.has(key)) { const oldest = this.revisions.keys().next().value!; this.revisions.delete(oldest); this.noteSignatures.delete(oldest); }
    this.revisions.set(key, ++this.sequence);
    for (const listener of this.listeners) listener(patientId, encounterId);
  }
  observeNotes(data: {patientId: string; encounterId: string; total: number; status: string; complete: boolean | null; lastSuccessfulAt: string | null; lastCompleteAt: string | null}) {
    if (!data.patientId || !data.encounterId) return;
    const key = this.key(data.patientId, data.encounterId);
    const signature = JSON.stringify([data.total, data.status, data.complete, data.lastSuccessfulAt, data.lastCompleteAt]);
    if (this.noteSignatures.get(key) === signature) return;
    this.notify(data.patientId, data.encounterId);
    this.noteSignatures.set(key, signature);
  }
}

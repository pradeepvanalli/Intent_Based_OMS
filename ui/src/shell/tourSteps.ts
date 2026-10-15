import { getSession } from '../auth/session';

/** One step of the first-run tour, anchored to a primary-nav item (by route). */
export interface TourStep { id: string; target: string; title: string; body: string; }

export const TOUR_STEPS: TourStep[] = [
  { id: 'control-tower', target: '/control-tower', title: 'Control Tower', body: 'Orders at risk, open exceptions, SLA timers and "Ask the OMS" in plain English. Start the day here.' },
  { id: 'playground', target: '/playground', title: 'Playground', body: 'Evaluate any decision on any input and watch L0 rules, Jev and the LLM layers hand off inside a risk tier.' },
  { id: 'orders', target: '/orders', title: 'Orders and the Why drawer', body: 'Open an order, then any decision on it: which rule fired, which question Jev answered, with what confidence, and what a person changed.' },
  { id: 'studio', target: '/studio', title: 'Policy Studio', body: 'Decisions are data the business owns: draft, lint, impact-diff on real inputs, golden gate, approvals, canary, rollback, kill switch.' },
  { id: 'copilot', target: '/copilot', title: 'Copilot', body: 'Describe a policy in plain English and get a governed ChangeSet with diffs and tests. Nothing is published by the model.' },
  { id: 'showcase', target: '/showcase', title: 'Showcase', body: 'A guided catalogue of the demo scenarios, reference tenants and features — with the command that produces each story.' },
];

export const TOUR_RESTART_EVENT = 'oms:tour:restart';
export const tourKey = (tenant: string): string => `oms.tour.seen.${tenant}`;

export function hasSeenTour(tenant: string | null | undefined): boolean {
  if (!tenant) return true;
  try { return localStorage.getItem(tourKey(tenant)) === '1'; } catch { return true; }
}
export function markTourSeen(tenant: string | null | undefined): void {
  if (!tenant) return;
  try { localStorage.setItem(tourKey(tenant), '1'); } catch { /* storage unavailable */ }
}
export function clearTourSeen(tenant: string | null | undefined): void {
  if (!tenant) return;
  try { localStorage.removeItem(tourKey(tenant)); } catch { /* storage unavailable */ }
}

/** Index of the next step, or null when the tour is finished. */
export function advance(step: number, total = TOUR_STEPS.length): number | null { return step + 1 < total ? step + 1 : null; }
export function back(step: number): number { return Math.max(0, step - 1); }

export interface Rect { top: number; left: number; right: number; bottom: number; width: number; height: number; }
/**
 * Where to put the step card: to the right of the anchor's rect, vertically aligned with it and clamped to the viewport.
 * Without an anchor (nav item hidden by permissions or not yet shipped) the card sits near the top of the main area.
 */
export function cardPosition(anchor: Rect | null, viewport: { width: number; height: number }, card = { width: 340, height: 190 }): { top: number; left: number } {
  const gap = 12; const margin = 8;
  let top: number; let left: number;
  if (anchor) { top = anchor.top - 8; left = anchor.right + gap; }
  else { top = 72; left = Math.min(260, Math.max(margin, viewport.width * 0.2)); }
  if (left + card.width > viewport.width - margin) left = Math.max(margin, viewport.width - card.width - margin);
  if (top + card.height > viewport.height - margin) top = Math.max(margin, viewport.height - card.height - margin);
  if (top < margin) top = margin;
  return { top: Math.round(top), left: Math.round(left) };
}

/** Clear the "seen" flag for the current tenant and ask the mounted Tour to open at step 1. */
export function restartTour(tenant?: string | null): void {
  const t = tenant ?? getSession().principal?.tenantId;
  clearTourSeen(t);
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(TOUR_RESTART_EVENT));
}

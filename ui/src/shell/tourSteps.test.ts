import { describe, expect, it, vi } from 'vitest';
import { advance, back, cardPosition, clearTourSeen, hasSeenTour, markTourSeen, restartTour, TOUR_RESTART_EVENT, TOUR_STEPS, tourKey } from './tourSteps';

describe('tour steps', () => {
  it('has six steps in the documented order, each anchored to a nav route', () => {
    expect(TOUR_STEPS.map((s) => s.target)).toEqual(['/control-tower', '/playground', '/orders', '/studio', '/copilot', '/showcase']);
    for (const s of TOUR_STEPS) { expect(s.title).toBeTruthy(); expect(s.body.length).toBeGreaterThan(20); }
  });
  it('advances to the next step and ends with null; back never goes below zero', () => {
    expect(advance(0)).toBe(1);
    expect(advance(4)).toBe(5);
    expect(advance(5)).toBeNull();
    expect(advance(0, 1)).toBeNull();
    expect(back(0)).toBe(0);
    expect(back(3)).toBe(2);
  });
});

describe('tour seen flag (per tenant, localStorage)', () => {
  it('is unseen for a new tenant, seen after marking, unseen after clearing, and independent per tenant', () => {
    expect(hasSeenTour('demo')).toBe(false);
    markTourSeen('demo');
    expect(hasSeenTour('demo')).toBe(true);
    expect(hasSeenTour('retail')).toBe(false);
    expect(localStorage.getItem(tourKey('demo'))).toBe('1');
    clearTourSeen('demo');
    expect(hasSeenTour('demo')).toBe(false);
  });
  it('treats a missing tenant as seen (never shows the tour before login)', () => {
    expect(hasSeenTour(null)).toBe(true);
    expect(hasSeenTour(undefined)).toBe(true);
  });
  it('survives a throwing storage', () => {
    const real = globalThis.localStorage;
    const throwing = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } } as unknown as Storage;
    Object.defineProperty(globalThis, 'localStorage', { value: throwing, configurable: true, writable: true });
    try {
      expect(hasSeenTour('demo')).toBe(true);
      expect(() => markTourSeen('demo')).not.toThrow();
      expect(() => clearTourSeen('demo')).not.toThrow();
    } finally { Object.defineProperty(globalThis, 'localStorage', { value: real, configurable: true, writable: true }); }
  });
  it('restartTour clears the flag and dispatches the restart event', () => {
    markTourSeen('demo');
    const h = vi.fn(); window.addEventListener(TOUR_RESTART_EVENT, h);
    restartTour('demo');
    expect(hasSeenTour('demo')).toBe(false);
    expect(h).toHaveBeenCalledTimes(1);
    window.removeEventListener(TOUR_RESTART_EVENT, h);
  });
});

describe('card position', () => {
  const vp = { width: 1400, height: 900 };
  it('sits to the right of the anchor, aligned with it', () => {
    expect(cardPosition({ top: 100, left: 8, right: 220, bottom: 130, width: 212, height: 30 }, vp)).toEqual({ top: 92, left: 232 });
  });
  it('falls back near the main area without an anchor', () => {
    const p = cardPosition(null, vp);
    expect(p.top).toBe(72); expect(p.left).toBeGreaterThan(0);
  });
  it('clamps to the viewport', () => {
    const p = cardPosition({ top: 880, left: 1300, right: 1390, bottom: 900, width: 90, height: 20 }, vp, { width: 340, height: 190 });
    expect(p.left + 340).toBeLessThanOrEqual(vp.width);
    expect(p.top + 190).toBeLessThanOrEqual(vp.height);
    expect(cardPosition({ top: -40, left: 0, right: 100, bottom: 0, width: 100, height: 40 }, vp).top).toBe(8);
  });
});

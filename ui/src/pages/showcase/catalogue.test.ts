import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CATALOGUE, PLANNED_ROUTES, linkPath, routeMatches } from './catalogue';

const APP_TSX = readFileSync(resolve(__dirname, '../../App.tsx'), 'utf8');
const APP_ROUTES = [...APP_TSX.matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]).filter((p) => p !== '*');
const REPO_ROOT = resolve(__dirname, '../../../../') + '/';

describe('showcase catalogue', () => {
  it('has unique ids and the seven demo scenarios plus three reference tenants', () => {
    const ids = CATALOGUE.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(CATALOGUE.filter((c) => c.kind === 'demo').map((c) => c.id)).toEqual(['uc1', 'uc2', 'uc3', 'uc4', 'uc5', 'uc6', 'uc7']);
    const refs = CATALOGUE.filter((c) => c.kind === 'reference');
    expect(refs.map((c) => c.tenant?.id)).toEqual(['retail', 'electronics', 'apparel']);
    for (const r of refs) { expect(r.scenarios).toHaveLength(5); expect(r.command).toBe(`scripts/reference/${r.tenant!.id}.sh`); }
  });
  it('points every deep link at a route that exists in App.tsx (or a planned route)', () => {
    expect(APP_ROUTES.length).toBeGreaterThan(10);
    for (const card of CATALOGUE) {
      expect(card.links.length).toBeGreaterThan(0);
      for (const l of card.links) {
        const path = linkPath(l.to);
        const known = APP_ROUTES.some((r) => routeMatches(path, r)) || PLANNED_ROUTES.includes(path);
        expect(known, `${card.id}: ${l.to} is not a known route`).toBe(true);
        expect(l.to.startsWith('/')).toBe(true);
      }
    }
  });
  it('references demo scripts that exist in scripts/usecases', () => {
    for (const card of CATALOGUE.filter((c) => c.kind === 'demo')) {
      expect(card.command).toMatch(/^scripts\/usecases\/uc\d-[a-z-]+\.sh$/);
      expect(existsSync(REPO_ROOT + card.command!), `${card.command} missing`).toBe(true);
    }
  });
  it('matches concrete paths against router patterns', () => {
    expect(routeMatches('/studio/return_risk', '/studio/:name')).toBe(true);
    expect(routeMatches('/studio', '/studio/:name')).toBe(false);
    expect(routeMatches('/orders', '/orders')).toBe(true);
    expect(routeMatches('/orders/x/y', '/orders/:id')).toBe(false);
    expect(linkPath('/analytics?tab=ai-economics')).toBe('/analytics');
  });
  it('leads the catalogue with the Agentic Control Tower flagship card', () => {
    expect(CATALOGUE[0].id).toBe('control-tower');
    expect(CATALOGUE[0].kind).toBe('feature');
  });
  it('titles the flagship card after the Agentic Control Tower and states the business value', () => {
    const card = CATALOGUE.find((c) => c.id === 'control-tower')!;
    expect(card.title).toMatch(/Agentic Control Tower/);
    expect(card.value.length).toBeGreaterThan(20);
  });
  it('points the flagship card at the /control-tower route', () => {
    const card = CATALOGUE.find((c) => c.id === 'control-tower')!;
    expect(card.links.length).toBeGreaterThan(0);
    expect(card.links.some((l) => linkPath(l.to) === '/control-tower')).toBe(true);
  });
});

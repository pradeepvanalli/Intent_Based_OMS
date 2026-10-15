import { describe, expect, it } from 'vitest';
import { aiUnavailable, dismissNoKey, isNoKeyDismissed } from './aiStatus';
import { appVersion, buildDiagnostics } from './diagnostics';

describe('aiUnavailable', () => {
  it('is false when Jev or any LLM provider is available', () => {
    expect(aiUnavailable({ jev: true, heuristic: true }, null)).toBe(false);
    expect(aiUnavailable({ jev: false, 'llm.openai': true, heuristic: true }, { available: false })).toBe(false);
  });
  it('is false when the copilot has a model even if providers are unknown', () => {
    expect(aiUnavailable(null, { available: true })).toBe(false);
  });
  it('is true when only the heuristic fallback remains and the copilot is off', () => {
    expect(aiUnavailable({ jev: false, 'llm.anthropic': false, 'llm.openai': false, heuristic: true }, { available: false })).toBe(true);
    expect(aiUnavailable({ heuristic: true }, null)).toBe(true);
    expect(aiUnavailable(null, { available: false })).toBe(true);
  });
  it('never nags when nothing could be read', () => {
    expect(aiUnavailable(null, null)).toBe(false);
    expect(aiUnavailable(undefined, undefined)).toBe(false);
  });
  it('remembers a dismissal per tenant for the session', () => {
    expect(isNoKeyDismissed('demo')).toBe(false);
    dismissNoKey('demo');
    expect(isNoKeyDismissed('demo')).toBe(true);
    expect(isNoKeyDismissed('retail')).toBe(false);
    sessionStorage.clear();
  });
});

describe('diagnostics', () => {
  it('reads the app version from actuator info shapes', () => {
    expect(appVersion({ app: { version: '0.1.0' }, git: { commit: { id: 'abcdef1234567' } } })).toBe('0.1.0 (abcdef12)');
    expect(appVersion({ build: { version: '0.2.0' } })).toBe('0.2.0');
    expect(appVersion({})).toBe('unknown');
    expect(appVersion(null)).toBe('unknown');
  });
  it('builds a pasteable block with tenant, providers and browser but no order data', () => {
    const text = buildDiagnostics({ uiVersion: '0.1.0', mock: true, appInfo: { app: { version: '0.1.0' } }, tenant: 'demo', tenantName: 'Demo Retail', user: 'admin', roles: ['admin'], providers: { jev: true, 'llm.anthropic': false }, copilot: { available: true, model: 'm' }, userAgent: 'UA', path: '/orders', now: '2026-10-14T00:00:00Z' });
    expect(text).toContain('Tenant: demo (Demo Retail)');
    expect(text).toContain('jev=on, llm.anthropic=off');
    expect(text).toContain('available=yes model=m');
    expect(text).toContain('(mock API)');
    expect(text).toContain('Browser: UA');
    expect(buildDiagnostics({ uiVersion: '0.1.0', mock: false, appInfo: null, providers: null, copilot: null, userAgent: 'UA', path: '/', now: 'now' })).toContain('unavailable (no permission or offline)');
  });
});

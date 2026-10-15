import type { ProviderMap } from './aiStatus';

export interface DiagnosticsInput {
  uiVersion: string; mock: boolean; appInfo: unknown;
  tenant?: string | null; tenantName?: string | null; user?: string | null; roles?: string[];
  providers: ProviderMap | null; copilot: { available?: boolean; model?: string; baseUrl?: string } | null;
  userAgent: string; path: string; now: string;
}

/** Best-effort app version from Spring Boot's /actuator/info (`app.version`, `build.version`, or the git commit). */
export function appVersion(info: unknown): string {
  if (!info || typeof info !== 'object') return 'unknown';
  const i = info as { app?: { version?: string; name?: string }; build?: { version?: string }; git?: { commit?: { id?: string | { abbrev?: string } } } };
  const v = i.app?.version ?? i.build?.version;
  const commit = typeof i.git?.commit?.id === 'string' ? i.git.commit.id : i.git?.commit?.id?.abbrev;
  if (v && commit) return `${v} (${String(commit).slice(0, 8)})`;
  return v ?? (commit ? String(commit).slice(0, 8) : 'unknown');
}

/** Plain-text block to paste into an issue: versions, tenant, provider status, browser. No order or customer data. */
export function buildDiagnostics(d: DiagnosticsInput): string {
  const providers = d.providers ? Object.entries(d.providers).map(([k, v]) => `${k}=${v ? 'on' : 'off'}`).join(', ') : 'unavailable (no permission or offline)';
  const copilot = d.copilot ? `available=${d.copilot.available ? 'yes' : 'no'}${d.copilot.model ? ` model=${d.copilot.model}` : ''}${d.copilot.baseUrl ? ` via ${d.copilot.baseUrl}` : ''}` : 'unavailable';
  return [
    '### Intent OMS diagnostic info',
    `- App: ${appVersion(d.appInfo)} · UI: ${d.uiVersion}${d.mock ? ' (mock API)' : ''}`,
    `- Tenant: ${d.tenant ?? '—'}${d.tenantName && d.tenantName !== d.tenant ? ` (${d.tenantName})` : ''} · User: ${d.user ?? '—'}${d.roles?.length ? ` [${d.roles.join(', ')}]` : ''}`,
    `- Decision providers: ${providers}`,
    `- Copilot: ${copilot}`,
    `- Browser: ${d.userAgent}`,
    `- Page: ${d.path} · ${d.now}`,
  ].join('\n');
}

/** Provider availability as returned by GET /api/v1/decisions/providers (name → available). */
export type ProviderMap = Record<string, boolean>;

/**
 * True when neither Jev nor an LLM is available for decisions and the copilot has no model either — i.e. the system
 * is running on L0 rules and the heuristic fallback. Unknown inputs (both null) never trigger the banner.
 */
export function aiUnavailable(providers: ProviderMap | null | undefined, copilot: { available?: boolean } | null | undefined): boolean {
  if (!providers && !copilot) return false;
  const decisionAi = providers ? Object.entries(providers).some(([name, ok]) => ok && (name === 'jev' || name.startsWith('llm'))) : false;
  const copilotAi = !!copilot?.available;
  return !decisionAi && !copilotAi;
}

export const noKeyDismissKey = (tenant: string): string => `oms.nokey.dismissed.${tenant}`;
export function isNoKeyDismissed(tenant: string | null | undefined): boolean {
  if (!tenant) return false;
  try { return sessionStorage.getItem(noKeyDismissKey(tenant)) === '1'; } catch { return false; }
}
export function dismissNoKey(tenant: string | null | undefined): void {
  if (!tenant) return;
  try { sessionStorage.setItem(noKeyDismissKey(tenant), '1'); } catch { /* storage unavailable */ }
}

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { copilotApi } from '../api/copilot';
import { hasPermission, useSession } from '../auth/session';
import { IconClose, IconExternal } from '../components/Icons';
import { GETTING_STARTED_URL } from '../lib/links';
import { aiUnavailable, dismissNoKey, isNoKeyDismissed, type ProviderMap } from './aiStatus';

/** Dismissible banner shown when neither Jev nor an LLM is configured: the system runs on rules + heuristics. */
export function NoKeyBanner() {
  const { principal } = useSession();
  const tenant = principal?.tenantId;
  const [dismissed, setDismissed] = useState(() => isNoKeyDismissed(tenant));
  const status = useQuery({
    queryKey: ['ai-availability', tenant],
    enabled: !!tenant && !dismissed,
    staleTime: 60_000, refetchInterval: 120_000, retry: false,
    queryFn: async () => {
      const [providers, copilot] = await Promise.all([
        hasPermission(principal, 'decision.read') ? api.get<ProviderMap>('/api/v1/decisions/providers', { silent: true }).catch(() => null) : Promise.resolve(null),
        copilotApi.status().catch(() => null),
      ]);
      return { providers, copilot };
    },
  });
  if (!tenant || dismissed || !status.data || !aiUnavailable(status.data.providers, status.data.copilot)) return null;
  return (
    <div className="callout warn" role="status" data-testid="no-key-banner" style={{ margin: 'var(--s4) var(--s6) 0', alignItems: 'center' }}>
      <span style={{ flex: 1 }}>
        <b>Running on rules + heuristics.</b> Add <code>OPENROUTER_API_KEY</code> (or <code>TYPESAFE_API_KEY</code> / <code>ANTHROPIC_API_KEY</code>) to switch on Jev and the LLM layers →{' '}
        <a href={GETTING_STARTED_URL} target="_blank" rel="noreferrer">docs/GETTING_STARTED.md <IconExternal size={12} /></a>
      </span>
      <button className="btn btn-sm btn-ghost btn-icon" onClick={() => { dismissNoKey(tenant); setDismissed(true); }} aria-label="Dismiss" title="Dismiss for this session"><IconClose size={14} /></button>
    </div>
  );
}

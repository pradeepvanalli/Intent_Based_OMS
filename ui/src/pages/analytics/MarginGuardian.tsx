import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { DigestFinding, MarginDigest } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select } from '../../components/Form';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { IconSparkle } from '../../components/Icons';
import { fmtDateTime, fmtUsd, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';

/** Margin Guardian: periodic copilot digest of margin leaks (splits, below-floor approvals, refund timing, escalations, AI spend). */

/** The digest's `facts.orders` is a number in the mock but an object `{orders, revenue, …}` from the backend. */
function ordersCount(v: unknown): number | undefined {
  if (typeof v === 'number') return v;
  if (v && typeof v === 'object' && typeof (v as { orders?: unknown }).orders === 'number') return (v as { orders: number }).orders;
  return undefined;
}

export function MarginGuardian() {
  const qc = useQueryClient(); const navigate = useNavigate();
  const [days, setDays] = useState(7);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const latest = useQuery({ queryKey: ['copilot-digest', 'latest'], queryFn: copilotApi.digestLatest });
  const history = useQuery({ queryKey: ['copilot-digest', 'history'], queryFn: () => copilotApi.digestHistory(10) });
  const run = useMutation({ mutationFn: () => copilotApi.digestRun(days), onSuccess: (d) => { toast.ok('Digest generated', d.headline.slice(0, 120)); qc.setQueryData(['copilot-digest', 'latest'], d); qc.invalidateQueries({ queryKey: ['copilot-digest'] }); qc.invalidateQueries({ queryKey: ['copilot-status'] }); setSelectedId(null); } });
  const items = history.data ?? [];
  const digest: MarginDigest | null | undefined = selectedId ? items.find((d) => d.id === selectedId) ?? latest.data : latest.data;
  const openInCopilot = (f: DigestFinding) => { if (f.suggestedPrompt) navigate(`/copilot?prompt=${encodeURIComponent(f.suggestedPrompt)}`); };
  return (
    <Section title={<span className="row gap3"><IconSparkle size={14} style={{ color: 'var(--ai)' }} /> Margin Guardian <span className="muted small" style={{ fontWeight: 400 }}>an AI digest of where margin leaked this period, with a fix to try for each finding</span></span>}
      actions={<form className="row gap3" onSubmit={(e) => { e.preventDefault(); run.mutate(); }}><Select aria-label="Digest period" sm value={String(days)} onChange={(e) => setDays(Number(e.target.value))}><option value="7">last 7 days</option><option value="30">last 30 days</option></Select><Button type="submit" size="sm" variant="ai" loading={run.isPending} icon={<IconSparkle size={13} />}>Run digest</Button></form>}>
      {latest.isLoading && <Loading label="Loading digest…" />}
      {latest.isError && <ErrorState error={latest.error} retry={() => latest.refetch()} />}
      {run.isError && <ErrorState error={run.error} />}
      {!latest.isLoading && !digest && <EmptyState icon={<IconSparkle size={18} />} title="No digest yet" hint="Run the digest to get a plain-English summary of where margin leaked this period — each finding comes with a suggested Copilot prompt so you can act on it in one click." />}
      {digest && (
        <div className="digest-layout">
          <div className="col gap4">
            <div className="col gap2">
              <div className="row wrap gap3">
                <Badge tone={digest.generated ? 'outline' : 'warn'}>{digest.generated ? 'generated' : 'deterministic'}</Badge>
                <span className="xs muted mono">{digest.model}</span>
                <Badge tone="outline">{digest.period.days} days · {digest.period.from} → {digest.period.to}</Badge>
                <span className="xs muted">generated {fmtDateTime(digest.generatedAt)}{ordersCount(digest.facts.orders) !== undefined ? ` · ${ordersCount(digest.facts.orders)} orders` : ''}</span>
              </div>
              {digest.fallbackReason && <div className="callout warn"><span>{digest.fallbackReason}</span></div>}
              <p className="digest-head">{digest.headline}</p>
            </div>
            <div className="grid grid-3" role="list" aria-label="Digest findings">
              {digest.findings.map((f, i) => <article key={i} className="finding" style={{ borderTop: '2.5px solid var(--ai)' }} role="listitem" aria-labelledby={`finding-${digest.id}-${i}`}>
                <span className="metric">{f.metric}</span>
                <span className="value">{typeof f.value === 'number' ? f.value.toLocaleString() : f.value}</span>
                <span id={`finding-${digest.id}-${i}`} className="title">{f.title}</span>
                {f.delta !== undefined && f.delta !== null && f.delta !== '' && <Badge tone="neutral">{String(f.delta)}</Badge>}
                {f.evidence && <span className="evidence">{f.evidence}</span>}
                {f.suggestedPrompt && <div className="mt2"><Button size="sm" variant="ai" icon={<IconSparkle size={13} />} onClick={() => openInCopilot(f)} title={f.suggestedPrompt}>Open in Copilot</Button></div>}
              </article>)}
            </div>
            {digest.facts.aiCost && <div className="xs muted">AI spend in period: decisions {fmtUsd(digest.facts.aiCost.decisions_usd)} · copilot {fmtUsd(digest.facts.aiCost.copilot_usd)} · total {fmtUsd(digest.facts.aiCost.total_usd)}{digest.facts.aiCost.budget_usd_per_day ? ` · budget ${fmtUsd(digest.facts.aiCost.budget_usd_per_day, 0)}/day` : ''}</div>}
          </div>
          <div className="card card-pad col gap2">
            <div className="section-title">History</div>
            {history.isLoading && <Loading inline />}
            {items.length === 0 && !history.isLoading && <span className="small muted">No stored digests.</span>}
            {items.map((d) => <button key={d.id} type="button" className="digest-item" aria-label={`Digest ${d.period.from} to ${d.period.to}`} aria-current={d.id === digest.id || undefined} onClick={() => setSelectedId(d.id)}><div className="row gap3"><Badge tone={d.generated ? 'outline' : 'warn'}>{d.period.days}d</Badge><span className="small">{d.period.from} → {d.period.to}</span><span className="grow" /><span className="xs muted">{relTime(d.generatedAt)}</span></div><div className="xs muted truncate" title={d.headline}>{d.headline}</div></button>)}
          </div>
        </div>
      )}
    </Section>
  );
}

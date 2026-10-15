import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { copilotApi } from '../../api/copilot';
import type { CsDraft } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Textarea } from '../../components/Form';
import { CopyButton, KeyValue } from '../../components/Misc';
import { EmptyState, ErrorState } from '../../components/States';
import { IconSparkle, IconWhy } from '../../components/Icons';
import { fmtConf, humanize, valueLabel } from '../../lib/format';

const EXAMPLES = ['Hi, where is my order? It was supposed to arrive yesterday.', 'The hoodie arrived with a tear in the sleeve. I want a refund.', 'Please cancel my order, I ordered the wrong size.', 'I moved last week, can you deliver to my new address instead?'];
const INTENT_TONE: Record<string, string> = { wismo: 'info', refund_request: 'warn', cancel_request: 'risk', address_change: 'hold', damaged_item: 'danger', other: 'neutral' };

/** CS reply draft (copilot): paste the customer's message, get an intent + grounded draft the agent edits before sending. */
export function CsDraftTab({ orderId, onWhy }: { orderId: string; onWhy: (recordId: string) => void }) {
  const [message, setMessage] = useState('');
  const [draft, setDraft] = useState('');
  const run = useMutation({ mutationFn: () => copilotApi.csDraft(orderId, message.trim()), onSuccess: (r) => setDraft(r.draft) });
  const r: CsDraft | undefined = run.data;
  return (
    <div className="draft-grid">
      <div className="col gap3">
        <form className="col gap3" onSubmit={(e) => { e.preventDefault(); if (message.trim()) run.mutate(); }}>
          <Textarea label="Customer message" hint="Paste their own words, or pick an example below — the reply is drafted only from this order's data, never invented." rows={4} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Paste the customer's message…" />
          <div className="example-chips" aria-label="Example messages">{EXAMPLES.map((ex) => <button key={ex} type="button" className="chip chip-btn" onClick={() => setMessage(ex)}>{ex}</button>)}</div>
          <div className="row wrap gap3"><span className="xs muted grow">You review and edit the draft below; nothing is sent to the customer automatically.</span><Button type="submit" variant="primary" icon={<IconSparkle size={14} />} loading={run.isPending} disabled={!message.trim()}>Draft reply</Button></div>
        </form>
        {run.isError && <ErrorState error={run.error} />}
        {!r && !run.isPending && <EmptyState icon={<IconSparkle size={18} />} title="No draft yet" hint="Paste the customer's message above and the copilot classifies the intent, then drafts a reply grounded in this order's own facts for you to edit and send." />}
        {r && <div className="col gap3">
          <div className="row wrap gap3">
            <span className="small muted">Intent</span>
            <Badge tone={INTENT_TONE[r.intent.value] ?? 'neutral'} lg aria-label={`intent ${humanize(r.intent.value)}`}>{humanize(r.intent.value)}</Badge>
            <span className="small muted">confidence <b className="num">{fmtConf(r.intent.confidence)}</b></span>
            {r.intent.decisionRecordId && <button type="button" className="btn btn-sm btn-ghost" onClick={() => onWhy(r.intent.decisionRecordId!)} aria-label="Why this intent"><IconWhy size={14} /> Why</button>}
          </div>
          <Textarea label={<span className="row gap3">Draft reply <Badge tone="outline">generated · editable</Badge></span>} rows={11} value={draft} onChange={(e) => setDraft(e.target.value)} />
          <div className="row wrap gap3"><CopyButton text={draft} label="Copy reply" /><Button size="sm" variant="ghost" onClick={() => setDraft(r.draft)} disabled={draft === r.draft}>Reset to generated</Button><span className="grow" /><span className="xs muted num">{draft.length} chars</span></div>
          <div className="callout info"><span>{r.disclaimer}</span></div>
        </div>}
      </div>
      <div className="col gap3">
        <div className="card card-pad col gap2">
          <div className="section-title">Facts used</div>
          {!r && <span className="small muted">Facts the draft quotes from the order appear here.</span>}
          {r && (r.factsUsed.length ? <div className="pill-row">{r.factsUsed.map((f) => <span key={f} className="chip mono" title={f}>{f}</span>)}</div> : <span className="small muted">No order facts needed.</span>)}
        </div>
        <div className="card card-pad col gap2">
          <div className="section-title">Slots</div>
          {!r && <span className="small muted">Promise date, refund amount, status and tracking resolved from the order.</span>}
          {r && <KeyValue tight items={[['Status', r.slots.status ? humanize(String(r.slots.status)) : '—'], ['Promise date', r.slots.promiseDate ?? '—'], ['Tracking', r.slots.tracking ? <span className="mono small">{String(r.slots.tracking)}</span> : '—'], ['Refund amount', r.slots.refundAmount !== undefined ? <span className="num">{typeof r.slots.refundAmount === 'number' ? `$${r.slots.refundAmount.toFixed(2)}` : valueLabel(r.slots.refundAmount)}</span> : '—']]} />}
        </div>
      </div>
    </div>
  );
}

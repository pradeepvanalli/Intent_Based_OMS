import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { ObjectiveProfile, ObjectiveWeights } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Range, Textarea } from '../../components/Form';
import { ErrorState, Loading } from '../../components/States';
import { useSession } from '../../auth/session';
import { fmtDate, fmtDateTime, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { DemoBadge, InfoTip, Meter, Technical } from './Bits';
import { personName } from './copy';

const TERMS: { key: keyof ObjectiveWeights; label: string; hint: string }[] = [
  { key: 'fill', label: 'Ship everything ordered', hint: 'Reward for shipping every unit that was ordered' },
  { key: 'otd', label: 'Deliver on time', hint: 'Reward for arriving on or before the promised date' },
  { key: 'cost', label: 'Keep costs down', hint: 'Penalty for fulfilment and shipping cost, relative to order value' },
  { key: 'split', label: 'Avoid split shipments', hint: 'Penalty for sending one order in several boxes when it was avoidable' },
  { key: 'fraud', label: 'Avoid fraud loss', hint: 'Penalty for chargebacks, refused deliveries and abusive returns' },
  { key: 'cx', label: 'Avoid customer contacts', hint: 'Penalty for "where is my order" and complaint contacts' },
  { key: 'inv', label: 'Sell aged stock', hint: 'Reward for selling stock that has been sitting for a while' },
];
const PRESETS: Record<string, { label: string; w: ObjectiveWeights }> = {
  balanced: { label: 'Balanced', w: { fill: 1, otd: 1, cost: 1, split: 1, fraud: 1, cx: 1, inv: 1 } },
  margin_first: { label: 'Margin first', w: { fill: 0.8, otd: 1.0, cost: 1.6, split: 0.9, fraud: 1.3, cx: 0.6, inv: 0.7 } },
  speed_first: { label: 'Speed first', w: { fill: 0.9, otd: 1.6, cost: 0.8, split: 0.5, fraud: 1.0, cx: 0.6, inv: 0.5 } },
};
type Window = { from: string; to: string };

/**
 * XP-07: what "a good outcome" means for this business. Anyone with learning admin drafts a change (in plain
 * English or with the sliders); it only takes effect when a different person approves it.
 */
export function ObjectiveSettings({ canAdmin }: { canAdmin: boolean }) {
  const live = useQuery({ queryKey: ['learning', 'objective'], queryFn: learningApi.objective });
  const drafts = useQuery({ queryKey: ['learning', 'objective', 'drafts'], queryFn: learningApi.objectiveDrafts });
  const history = useQuery({ queryKey: ['learning', 'objective', 'history'], queryFn: () => learningApi.objectiveHistory(10) });
  return (
    <div className="col gap4">
      {live.isLoading && <Loading />}
      {live.isError && <ErrorState error={live.error} retry={() => live.refetch()} />}
      {live.data && <LiveObjective p={live.data} />}
      {canAdmin && live.data && <DraftEditor base={live.data} />}
      <div>
        <div className="section-title">Drafts waiting for approval</div>
        {drafts.isLoading && <Loading inline />}
        {drafts.data && drafts.data.length === 0 && <span className="small muted">No drafts.</span>}
        <div className="col gap3">{(drafts.data ?? []).map((d) => <DraftCard key={d.version} d={d} live={live.data} canAdmin={canAdmin} />)}</div>
      </div>
      {history.data && history.data.length > 1 && (
        <details>
          <summary className="small">Earlier versions ({history.data.length})</summary>
          <div className="subtle-list small mt2">{history.data.map((h) => <div key={h.version} className="row-between"><span>Version {h.version} · {h.status.toLowerCase()}</span><span className="muted">{h.approvedBy ? `approved by ${personName(h.approvedBy)} ${fmtDate(h.approvedAt)}` : `drafted ${fmtDate(h.createdAt)}`}</span></div>)}</div>
        </details>
      )}
    </div>
  );
}

function LiveObjective({ p }: { p: ObjectiveProfile }) {
  const max = Math.max(2, ...TERMS.map((t) => p.weights[t.key]));
  const windows = freezeWindows(p);
  return (
    <div className="col gap2">
      <div className="row-between wrap">
        {p.version > 0
          ? <span className="small">In use: <b>version {p.version}</b>{p.approvedBy && <> · approved by {personName(p.approvedBy)} {relTime(p.approvedAt)}</>}</span>
          : <span className="small">Nothing approved yet, so the <b>balanced</b> default is in use.</span>}
        <DemoBadge by={p.createdBy} />
      </div>
      <div className="weight-bars">{TERMS.map((t) => (
        <div key={t.key} className="weight-bar"><span title={t.hint}>{t.label}</span><Meter value={p.weights[t.key] / max} label={`${t.label} weight`} /><span className="num right">{p.weights[t.key].toFixed(2)}</span></div>
      ))}</div>
      {windows.length > 0 && <div className="small">No automatic changes during: {windows.map((w) => `${fmtDate(w.from)} – ${fmtDate(w.to)}`).join(', ')}</div>}
      {p.sourceText && <div className="xs muted">Drafted from: “{p.sourceText}”</div>}
    </div>
  );
}

function freezeWindows(p: ObjectiveProfile): Window[] {
  const raw = (p.constraints ?? {}).freezeWindows;
  return Array.isArray(raw) ? raw.filter((w): w is Window => !!w && typeof w === 'object' && 'from' in w && 'to' in w).map((w) => ({ from: String(w.from), to: String(w.to) })) : [];
}

function DraftEditor({ base }: { base: ObjectiveProfile }) {
  const qc = useQueryClient();
  const [text, setText] = useState('');
  const [weights, setWeights] = useState<ObjectiveWeights>(base.weights);
  const [windows, setWindows] = useState<Window[]>(freezeWindows(base));
  useEffect(() => { setWeights(base.weights); setWindows(freezeWindows(base)); }, [base]);
  const invalidate = () => qc.invalidateQueries({ queryKey: ['learning', 'objective'] });
  const fromText = useMutation({ mutationFn: () => learningApi.draftObjectiveFromText(text.trim()), onSuccess: (d) => { toast.ok(`Draft version ${d.version} created`, 'Someone other than you must approve it.'); setText(''); invalidate(); } });
  const save = useMutation({
    mutationFn: () => learningApi.draftObjective(weights, { ...(base.constraints ?? {}), freezeWindows: windows.filter((w) => w.from && w.to) }),
    onSuccess: (d) => { toast.ok(`Draft version ${d.version} created`, 'Someone other than you must approve it.'); invalidate(); },
  });
  const dirty = TERMS.some((t) => weights[t.key] !== base.weights[t.key]) || JSON.stringify(windows) !== JSON.stringify(freezeWindows(base));
  return (
    <div className="card card-pad col gap3">
      <div>
        <div className="section-title">Describe what matters <InfoTip text="Write it the way you would say it to a colleague; the system turns it into weights and explains each one. Nothing changes until someone else approves the draft." /></div>
        <Textarea aria-label="Describe what matters" rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder="e.g. Peak season: on-time delivery matters most, cost matters less, never accept more fraud" />
        <div className="flex-end mt2"><Button size="sm" variant="ai" disabled={!text.trim()} loading={fromText.isPending} onClick={() => fromText.mutate()}>Draft from description</Button></div>
      </div>
      <details>
        <summary className="small">Or adjust the weights yourself</summary>
        <div className="col gap3 mt2">
          <div className="pill-row">{Object.entries(PRESETS).map(([k, v]) => <button key={k} type="button" className="chip chip-btn" onClick={() => setWeights(v.w)}>{v.label}</button>)}</div>
          <div className="grid grid-2 gap3">{TERMS.map((t) => (
            <Range key={t.key} label={<span title={t.hint}>{t.label}</span>} value={weights[t.key]} min={0} max={2} step={0.05} format={(v) => v.toFixed(2)} onChange={(v) => setWeights((w) => ({ ...w, [t.key]: v }))} />
          ))}</div>
          <div>
            <div className="section-title">No automatic changes during <InfoTip text="Freeze windows, such as peak season: nothing learned goes live automatically or by approval between these dates." /></div>
            <div className="col gap2">{windows.map((w, i) => (
              <div key={i} className="row gap2 wrap">
                <Input sm type="date" aria-label="Freeze from" value={w.from} onChange={(e) => setWindows((ws) => ws.map((x, j) => (j === i ? { ...x, from: e.target.value } : x)))} />
                <span className="muted">to</span>
                <Input sm type="date" aria-label="Freeze to" value={w.to} onChange={(e) => setWindows((ws) => ws.map((x, j) => (j === i ? { ...x, to: e.target.value } : x)))} />
                <Button size="sm" variant="ghost" onClick={() => setWindows((ws) => ws.filter((_, j) => j !== i))}>Remove</Button>
              </div>
            ))}<div><Button size="sm" variant="subtle" onClick={() => setWindows((ws) => [...ws, { from: '', to: '' }])}>Add a freeze window</Button></div></div>
          </div>
          <div className="row gap2"><Button size="sm" variant="primary" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate()}>Save as draft</Button>{dirty && <Button size="sm" variant="ghost" onClick={() => { setWeights(base.weights); setWindows(freezeWindows(base)); }}>Reset</Button>}</div>
        </div>
      </details>
    </div>
  );
}

function DraftCard({ d, live, canAdmin }: { d: ObjectiveProfile; live?: ObjectiveProfile; canAdmin: boolean }) {
  const qc = useQueryClient();
  const { principal } = useSession();
  const mine = principal ? d.createdBy === `user:${principal.id}` : false;
  const approve = useMutation({ mutationFn: () => learningApi.approveObjective(d.version), onSuccess: () => { toast.ok(`Version ${d.version} is now in use`); qc.invalidateQueries({ queryKey: ['learning', 'objective'] }); } });
  const changed = TERMS.filter((t) => !live || live.weights[t.key] !== d.weights[t.key]);
  return (
    <div className="finding" data-testid={`objective-draft-${d.version}`}>
      <div className="row-between wrap gap2">
        <span className="title">Draft version {d.version}</span>
        <span className="row gap2"><span className="xs muted">by {personName(d.createdBy)} {relTime(d.createdAt)}</span><DemoBadge by={d.createdBy} /></span>
      </div>
      {d.sourceText && <div className="small">“{d.sourceText}”</div>}
      {changed.length === 0 ? <span className="small muted">Same weights as the version in use.</span> : (
        <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>{changed.map((t) => (
          <li key={t.key}>{t.label}: {live ? `${live.weights[t.key].toFixed(2)} → ` : ''}<b>{d.weights[t.key].toFixed(2)}</b>{d.rationale?.[t.key] && <span className="muted"> — {d.rationale[t.key]}</span>}</li>
        ))}</ul>
      )}
      <Technical><span className="xs muted mono">method {d.draftMethod ?? 'manual'} · created {fmtDateTime(d.createdAt)}</span></Technical>
      {canAdmin && (
        <div className="row gap2 wrap">
          <Button size="sm" variant="primary" disabled={mine} loading={approve.isPending} onClick={() => approve.mutate()}>Approve and use</Button>
          {mine && <Badge tone="outline" className="wrap-text">You drafted this; someone else must approve</Badge>}
        </div>
      )}
    </div>
  );
}

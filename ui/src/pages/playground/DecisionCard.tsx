import { Badge, BandBadge, LayerBadge } from '../../components/Badge';
import { fmtConf, fmtUsd, humanize, valueLabel } from '../../lib/format';
import { labelFor, type TraceRecord } from './trace';

export function DecisionCard({ r, index, onWhy }: { r: TraceRecord; index: number; onWhy?: (id: string) => void }) {
  const pct = Math.max(0, Math.min(100, r.confidence * 100));
  const tone = r.band === 'ACT' ? 'ok' : r.band === 'CONFIRM' ? 'warn' : 'danger';
  return (
    <article className="pg-card enter" style={{ animationDelay: `${Math.min(index, 6) * 40}ms` }} aria-label={`${labelFor(r.decision)} decision`}>
      <header className="pg-card-head">
        <span className="pg-step">{index + 1}</span>
        <span className="pg-card-title">{labelFor(r.decision)}<span className="muted xs mono"> v{r.version}</span></span>
        <LayerBadge layer={r.layer} />
        <span title="act = decided on its own · confirm = double-checked by a model · escalate = sent to a person"><BandBadge band={r.band} /></span>
        {onWhy && <button className="btn btn-sm btn-ghost pg-why" onClick={() => onWhy(r.id)}>Why</button>}
      </header>
      <div className="pg-card-value">
        <span className="pg-value mono">{valueLabel(r.value)}</span>
        {r.requiresHuman && <Badge tone="L3">waiting for a person</Badge>}
        {r.layer === 'DEFAULT' && <Badge tone="outline">deterministic default</Badge>}
      </div>
      <div className="pg-conf" aria-label={`confidence ${fmtConf(r.confidence)}`} title="How sure the model is in this answer, out of 100">
        <span className="pg-conf-lbl xs muted">confidence</span>
        <div className={`bar ${tone}`}><span style={{ width: `${pct}%` }} /></div>
        <span className="num small">{fmtConf(r.confidence)}</span>
      </div>
      <dl className="pg-meta">
        <div><dt>Latency</dt><dd className="num">{r.latencyMs} ms</dd></div>
        <div><dt>Cost</dt><dd className="num">{r.costUsd > 0 ? fmtUsd(r.costUsd, 5) : '$0'}</dd></div>
        <div><dt>Provider</dt><dd className="mono truncate" title={`${r.provider}${r.modelVersion ? ` · ${r.modelVersion}` : ''}`}>{r.provider}{r.modelVersion ? <span className="muted"> · {r.modelVersion}</span> : null}</dd></div>
      </dl>
      {(r.reasonCodes.length > 0 || r.rulesFired.length > 0) && (
        <div className="pill-row">
          {r.rulesFired.map((x) => <Badge key={`rule-${x}`} tone="L0" mono>rule {x}</Badge>)}
          {r.reasonCodes.map((x) => <Badge key={`rc-${x}`} tone="outline">{humanize(x)}</Badge>)}
        </div>
      )}
    </article>
  );
}

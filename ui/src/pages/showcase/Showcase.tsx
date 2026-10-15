import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { clearSession, useSession } from '../../auth/session';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { CopyButton } from '../../components/Misc';
import { IconExternal } from '../../components/Icons';
import { DEMO_USE_CASES_URL, DOCS_URL, GETTING_STARTED_URL } from '../../lib/links';
import { restartTour } from '../../shell/tourSteps';
import { CATALOGUE, KIND_LABELS, type CardKind, type ShowcaseCard } from './catalogue';

const KINDS: CardKind[] = ['demo', 'reference', 'feature'];
const NEEDS: Record<string, { label: string; tone: 'ok' | 'info' | 'warn' }> = { none: { label: 'no key needed', tone: 'ok' }, jev: { label: 'best with Jev', tone: 'info' }, llm: { label: 'needs an LLM key', tone: 'warn' } };

/** Guided catalogue of what to look at: demo scenarios, reference tenants and standalone features, with deep links. */
export default function Showcase() {
  const { principal } = useSession();
  const navigate = useNavigate();
  const byKind = useMemo(() => KINDS.map((k) => ({ kind: k, cards: CATALOGUE.filter((c) => c.kind === k) })), []);
  const switchTenant = () => { clearSession(); navigate('/login'); };
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Showcase</h1><div className="sub">Where to look, in what order, and the command that produces each story. Everything works without an AI key; Jev and an LLM make the free-text parts autonomous.</div></div>
        <div className="page-actions">
          <Button onClick={() => restartTour()}>Start the tour</Button>
          <a className="btn" href={GETTING_STARTED_URL} target="_blank" rel="noreferrer">Getting started <IconExternal size={13} /></a>
        </div>
      </div>
      <div className="callout info"><span><b>How to run a story.</b> From the repo root: <code>make run</code> starts the service on :8080 with the seeded <code>demo</code> tenant; then run the card's script and follow its links. You are signed in to <code>{principal?.tenantId ?? '—'}</code>; reference tenants need a sign-out first. All scripts and talk tracks: <a href={DEMO_USE_CASES_URL} target="_blank" rel="noreferrer">docs/DEMO_USE_CASES.md</a>.</span></div>
      {byKind.map(({ kind, cards }) => (
        <section key={kind} className="col gap3" aria-labelledby={`showcase-${kind}`}>
          <div><h2 id={`showcase-${kind}`} style={{ fontSize: 'var(--fs-lg)', fontWeight: 650 }}>{KIND_LABELS[kind].title}</h2><div className="small muted">{KIND_LABELS[kind].blurb}</div></div>
          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))' }}>
            {cards.map((c) => <Card key={c.id} card={c} currentTenant={principal?.tenantId} onSwitchTenant={switchTenant} />)}
          </div>
        </section>
      ))}
      <div className="small muted">Missing a story? The catalogue is one typed file: <code>ui/src/pages/showcase/catalogue.ts</code>. Docs live under <a href={DOCS_URL} target="_blank" rel="noreferrer">docs/</a>.</div>
    </div>
  );
}

function Card({ card, currentTenant, onSwitchTenant }: { card: ShowcaseCard; currentTenant?: string; onSwitchTenant: () => void }) {
  const needs = NEEDS[card.needs ?? 'none'];
  const onTenant = !card.tenant || card.tenant.id === currentTenant;
  return (
    <article className="card card-pad col gap3" data-testid={`showcase-${card.id}`}>
      <div className="row gap2 wrap" style={{ alignItems: 'center' }}>
        <h3 style={{ fontSize: 'var(--fs-md)', fontWeight: 650, flex: 1, minWidth: 0 }}>{card.title}</h3>
        {card.tenant && <Badge tone="outline" title="Tenant id">⌂ {card.tenant.id}</Badge>}
        <Badge tone={needs.tone}>{needs.label}</Badge>
      </div>
      <p className="small" style={{ margin: 0, lineHeight: 1.55 }}>{card.value}</p>
      {card.scenarios && <ol className="small muted" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.6 }}>{card.scenarios.map((s) => <li key={s.id}><b className="mono">{s.id}</b> · {s.title}</li>)}</ol>}
      {card.command && <div className="row gap2" style={{ alignItems: 'center' }}><code className="code" style={{ flex: 1, padding: '6px 10px', fontSize: 'var(--fs-xs)', whiteSpace: 'nowrap', overflow: 'auto' }}>{card.command}</code><CopyButton text={card.command} label="Copy" /></div>}
      <div className="row gap2 wrap" style={{ alignItems: 'center' }}>
        <span className="small muted" style={{ marginRight: 4 }}>Look at</span>
        {card.links.map((l) => onTenant
          ? <Link key={l.to} to={l.to} className="btn btn-sm" title={l.hint}>{l.label}</Link>
          : <span key={l.to} className="btn btn-sm" aria-disabled="true" style={{ opacity: .55, cursor: 'not-allowed' }} title={`Sign in to tenant ${card.tenant?.id} first`}>{l.label}</span>)}
      </div>
      {card.tenant && !onTenant && <div className="row gap2" style={{ alignItems: 'center' }}><Button size="sm" variant="primary" onClick={onSwitchTenant}>Switch tenant</Button><span className="small muted">signs you out; enter <code>{card.tenant.id}</code> as the tenant on the login page</span></div>}
      {card.tenant && onTenant && <div className="small muted">You are on this tenant.</div>}
    </article>
  );
}

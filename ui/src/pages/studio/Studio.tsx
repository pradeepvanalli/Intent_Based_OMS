import { useQuery } from '@tanstack/react-query';
import { useNavigate, Link } from 'react-router-dom';
import { decisionsApi } from '../../api/decisions';
import { Badge, StatusBadge, TierBadge } from '../../components/Badge';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { IconSparkle, IconStudio } from '../../components/Icons';
import { fmtNum, fmtPct, relTime } from '../../lib/format';
import { StatTile } from '../../components/StatTile';

export default function Studio() {
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['decisions'], queryFn: decisionsApi.list });
  const d = q.data ?? [];
  const pending = d.filter((x) => x.status === 'pending_approval').length; const canary = d.filter((x) => x.status === 'canary' || x.status === 'shadow').length; const drafts = d.filter((x) => x.status === 'draft').length; const killed = d.filter((x) => x.killSwitch).length;
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Decision governance</div>
          <h1><span className="icon-badge"><IconStudio size={20} /></span> Policy Studio</h1>
          <div className="sub">Every automated judgment call the system makes — like which warehouse ships an order, or when to approve a return — lives here as a versioned "decision" you can test, approve and roll out gradually by risk tier (T0–T3).</div>
        </div>
      </div>
      <div className="grid grid-4">
        <StatTile label="Decisions" value={d.length} hint={`${d.filter((x) => x.liveVersion).length} live · ${drafts} draft${drafts === 1 ? '' : 's'}`} loading={q.isLoading} />
        <StatTile label="Awaiting approval" value={pending} tone={pending ? 'warn' : undefined} hint="waiting on a human sign-off before they can publish" loading={q.isLoading} />
        <StatTile label="In shadow / canary" value={canary} hint="being tried on real traffic before a full rollout" loading={q.isLoading} />
        <StatTile label="Kill switches on" value={killed} tone={killed ? 'danger' : 'ok'} hint="instantly stops a decision and falls back to its safe default" loading={q.isLoading} />
      </div>
      <div className="card">
        {q.isLoading && <Loading />}{q.isError && <div className="card-body"><ErrorState error={q.error} retry={() => q.refetch()} /></div>}
        {q.data && <Table rows={q.data} rowKey={(r) => r.name} onRowClick={(r) => navigate(`/studio/${encodeURIComponent(r.name)}`)} caption="Decisions"
          empty={<EmptyState icon={<IconStudio size={18} />} title="No decisions defined yet" hint="A decision is a reusable policy — like how to choose a warehouse or score a return risk — that this system can apply automatically. Ask Copilot to draft a starter set from a description of your business." action={<Link to="/copilot/bootstrap" className="btn btn-sm"><IconSparkle size={13} /> Bootstrap with copilot</Link>} />}
          columns={[
          { key: 'name', header: 'Decision', render: (r) => <span className="col gap1"><span className="mono strong">{r.name}</span><span className="xs muted truncate" style={{ maxWidth: 420, display: 'inline-block' }}>{r.purpose}</span></span> },
          { key: 'status', header: 'Status', render: (r) => <span className="row gap1"><StatusBadge status={r.status} />{r.killSwitch && <Badge tone="danger">kill switch</Badge>}</span> },
          { key: 'tier', header: <span title="Risk tier: T0 informational · T1 reversible · T2 customer-impacting · T3 money-moving — higher tiers need more approval and are never fully autonomous">Tier</span>, render: (r) => <TierBadge tier={r.tier} /> }, { key: 'owner', header: 'Owner', render: (r) => r.owner },
          { key: 'v', header: 'Version', render: (r) => <span className="mono small">live v{r.liveVersion ?? '—'}{r.latestVersion !== r.liveVersion && <span className="muted"> · latest v{r.latestVersion}</span>}</span> },
          { key: 'prov', header: <span title="Which engine answers this decision: Jev (fast, in-house model) or an LLM">Provider</span>, render: (r) => <Badge tone={r.provider === 'jev' ? 'L1' : 'L2'} mono>{r.provider}</Badge> },
          { key: 'vol', header: 'Volume 30d', align: 'right', render: (r) => fmtNum(r.volume30d) },
          { key: 'ovr', header: <span title="How often a human overturned this decision's answer">Override rate</span>, align: 'right', render: (r) => <span className={r.overrideRate !== undefined && r.overrideRate > 0.05 ? 'strong' : ''} style={r.overrideRate !== undefined && r.overrideRate > 0.05 ? { color: 'var(--danger)' } : undefined}>{fmtPct(r.overrideRate)}</span> },
          { key: 'esc', header: <span title="How often this decision was too unsure and was handed to a human instead">Escalation</span>, align: 'right', render: (r) => fmtPct(r.escalationRate) }, { key: 'upd', header: 'Updated', render: (r) => relTime(r.updatedAt) },
        ]} />}
      </div>
    </div>
  );
}

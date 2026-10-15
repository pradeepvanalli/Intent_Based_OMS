import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { stateModelsApi } from '../../api/stateModels';
import { Badge } from '../../components/Badge';
import { Table } from '../../components/Table';
import { EmptyState, ErrorState, Loading } from '../../components/States';
import { IconStates } from '../../components/Icons';
import { fmtDateTime } from '../../lib/format';

export default function StateModels() {
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['state-models'], queryFn: stateModelsApi.list });
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow">Workflow governance</div>
          <h1><span className="icon-badge"><IconStates size={20} /></span> State Models</h1>
          <div className="sub">The lifecycle every order, line or return moves through — created, released, shipped, cancelled, and so on — with SLA timers and rules for what can transition to what, configured per entity rather than hard-coded.</div>
        </div>
        <div className="page-actions"><button className="btn btn-primary" onClick={() => navigate('/state-models/new')}>Publish new model</button></div>
      </div>
      <div className="card">
        {q.isLoading && <Loading />}{q.isError && <div className="card-body"><ErrorState error={q.error} retry={() => q.refetch()} /></div>}
        {q.data && <Table rows={q.data} rowKey={(m) => m.id} onRowClick={(m) => navigate(`/state-models/${encodeURIComponent(m.id)}`)} caption="State models"
          empty={<EmptyState icon={<IconStates size={18} />} title="No state models published yet" hint="A state model defines the stages an entity (an order line, a return, …) can be in and what's allowed to move it between them. Publish one to get started." action={<button className="btn btn-sm btn-primary" onClick={() => navigate('/state-models/new')}>Publish new model</button>} />}
          columns={[
          { key: 'id', header: 'Model', render: (m) => <span className="mono strong">{m.id}</span> }, { key: 'v', header: 'Latest version', render: (m) => <Badge tone="accent" mono>v{m.version}</Badge> },
          { key: 'entity', header: 'Entity', render: (m) => <Badge tone="outline">{m.entity}</Badge> }, { key: 'ql', header: <span title="Tracked separately for each unit of quantity on the line, not just once per line">Quantity level</span>, render: (m) => m.quantityLevel ? <Badge tone="ok">yes</Badge> : <span className="muted">no</span> },
          { key: 's', header: 'States', align: 'right', render: (m) => m.stateCount ?? '—' }, { key: 't', header: <span title="Allowed moves from one state to another">Transitions</span>, align: 'right', render: (m) => m.transitionCount ?? '—' }, { key: 'inf', header: <span title="Entities currently pinned to this version">In flight</span>, align: 'right', render: (m) => m.inFlight ?? '—' },
          { key: 'p', header: 'Published', render: (m) => fmtDateTime(m.publishedAt) },
        ]} />}
      </div>
    </div>
  );
}

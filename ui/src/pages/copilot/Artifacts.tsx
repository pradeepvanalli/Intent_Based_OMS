import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { copilotApi } from '../../api/copilot';
import type { DraftArtifact } from '../../api/types';
import { hasPermission, useSession } from '../../auth/session';
import { Badge, StatusBadge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Select, Textarea } from '../../components/Form';
import { KeyValue, YamlView } from '../../components/Misc';
import { Dialog } from '../../components/Overlay';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { Table } from '../../components/Table';
import { IconLock } from '../../components/Icons';
import { fmtDateTime, humanize, relTime } from '../../lib/format';
import { useQueryState } from '../../lib/hooks';
import { toast } from '../../lib/toast';
import { KindBadge } from './ChangeSetView';
import { applyPermission } from './shared';


export function Artifacts() {
  const qc = useQueryClient(); const { principal } = useSession();
  const [status, setStatus] = useState('draft');
  const [selectedId, setSelectedId] = useQueryState('artifact');
  const q = useQuery({ queryKey: ['copilot-artifacts', status], queryFn: () => copilotApi.artifacts(status === 'all' ? undefined : status) });
  const items = useMemo(() => q.data?.items ?? [], [q.data]);
  const selected = items.find((a) => a.id === selectedId) ?? null;
  useEffect(() => { if (selectedId && q.data && !q.data.items.some((a) => a.id === selectedId) && status !== 'all') setStatus('all'); }, [selectedId, q.data, status]);
  const [dialog, setDialog] = useState<{ action: 'apply' | 'reject'; a: DraftArtifact } | null>(null); const [reason, setReason] = useState('');
  const act = useMutation({ mutationFn: ({ action, a }: { action: 'apply' | 'reject'; a: DraftArtifact }) => (action === 'apply' ? copilotApi.applyArtifact(a.id, reason) : copilotApi.rejectArtifact(a.id, reason)), onSuccess: (r, v) => { toast.ok(v.action === 'apply' ? 'Artifact applied' : 'Artifact rejected', `${humanize(r.kind)} ${r.target}`); setDialog(null); setReason(''); qc.invalidateQueries({ queryKey: ['copilot-artifacts'] }); qc.invalidateQueries({ queryKey: ['config'] }); } });
  return (
    <div className="two-col" style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(360px, 520px)' }}>
      <Section title={<span className="row gap3">Staged artefacts <span className="muted small" style={{ fontWeight: 400 }}>state models, order types, SLAs, playbooks, tenant config, compliance rules</span></span>} actions={<Select aria-label="Status filter" sm value={status} onChange={(e) => { setStatus(e.target.value); setSelectedId(''); }}><option value="draft">Draft</option><option value="applied">Applied</option><option value="rejected">Rejected</option><option value="all">All</option></Select>} tight>
        {q.isLoading && <Loading />}
        {q.isError && <ErrorState error={q.error} retry={() => q.refetch()} />}
        {q.data && <Table rows={items} rowKey={(a) => a.id} dense selectedKey={selectedId || null} onRowClick={(a) => setSelectedId(a.id)} caption="Staged artefacts" empty={<EmptyState title={status === 'draft' ? 'No draft artefacts' : 'Nothing here'} hint="Stage non-decision changes from a change set and they appear here for review and apply." />} columns={[
          { key: 'kind', header: 'Kind', render: (a) => <KindBadge kind={a.kind} /> },
          { key: 'target', header: 'Target', render: (a) => <span className="mono small">{a.target}</span> },
          { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
          { key: 'cs', header: 'Change set', render: (a) => <Link to={`/copilot?cs=${encodeURIComponent(a.changesetId)}`} className="mono small" onClick={(e) => e.stopPropagation()}>{a.changesetId}</Link> },
          { key: 'by', header: 'Created', render: (a) => <span className="small muted nowrap" title={fmtDateTime(a.createdAt)}>{a.createdBy} · {relTime(a.createdAt)}</span> },
        ]} />}
      </Section>
      <Section title={selected ? <span className="row gap3"><KindBadge kind={selected.kind} /><span className="mono">{selected.target}</span></span> : 'Artefact'} actions={selected && <StatusBadge status={selected.status} />} className="sticky-side">
        {!selected && <EmptyState title="Select an artefact" hint="The YAML or JSON is shown here with Apply and Reject actions." />}
        {selected && <ArtifactDetail a={selected} canApply={hasPermission(principal, applyPermission(selected.kind))} onApply={() => setDialog({ action: 'apply', a: selected })} onReject={() => setDialog({ action: 'reject', a: selected })} />}
      </Section>
      {dialog && <Dialog open onClose={() => setDialog(null)} title={dialog.action === 'apply' ? `Apply ${humanize(dialog.a.kind)} ${dialog.a.target}` : `Reject ${humanize(dialog.a.kind)} ${dialog.a.target}`} description={dialog.action === 'apply' ? 'Applying writes the artefact to the tenant and takes effect immediately. The reason is audited.' : 'The artefact stays in history as rejected.'} footer={<><Button onClick={() => setDialog(null)}>Cancel</Button><Button variant={dialog.action === 'apply' ? 'primary' : 'danger'} disabled={!reason.trim()} loading={act.isPending} onClick={() => act.mutate(dialog)}>{dialog.action === 'apply' ? 'Apply' : 'Reject'}</Button></>}>
        <Textarea label="Reason (required, audited)" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={dialog.action === 'apply' ? 'Ticket or why this is safe to apply now' : 'Why this artefact should not be applied'} />
      </Dialog>}
    </div>
  );
}

function ArtifactDetail({ a, canApply, onApply, onReject }: { a: DraftArtifact; canApply: boolean; onApply: () => void; onReject: () => void }) {
  const text = a.yaml ?? a.json ?? '';
  return (
    <div className="col gap3">
      <KeyValue tight items={[['Id', <span className="mono small">{a.id}</span>], ['Change set', <Link to={`/copilot?cs=${encodeURIComponent(a.changesetId)}`} className="mono small">{a.changesetId}</Link>], ['Created', `${a.createdBy} · ${fmtDateTime(a.createdAt)}`], ['Applied', a.appliedAt ? `${a.appliedBy} · ${fmtDateTime(a.appliedAt)}` : '—'], ['Format', <Badge tone="outline">{a.yaml ? 'YAML' : 'JSON'}</Badge>]]} />
      <YamlView text={text} maxHeight={440} />
      {a.status === 'draft' && <div className="row wrap gap3">
        <Button variant="primary" disabled={!canApply} onClick={onApply} icon={!canApply ? <IconLock size={13} /> : undefined} title={!canApply ? `Requires ${applyPermission(a.kind)}` : undefined}>Apply…</Button>
        <Button variant="danger" onClick={onReject}>Reject…</Button>
        {!canApply && <span className="xs muted">Applying a {humanize(a.kind)} needs <code>{applyPermission(a.kind)}</code>.</span>}
      </div>}
    </div>
  );
}

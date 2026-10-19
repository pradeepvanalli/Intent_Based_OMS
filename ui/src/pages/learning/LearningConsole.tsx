import { useQuery, useQueries, useMutation, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { ProposalStatus } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { IconSparkle } from '../../components/Icons';
import { TabPanel, Tabs } from '../../components/Tabs';
import { EmptyState } from '../../components/States';
import { hasPermission, useSession } from '../../auth/session';
import { useQueryState } from '../../lib/hooks';
import { relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { TechnicalToggle } from './Bits';
import { OverviewTab } from './OverviewTab';
import { LearnersTab } from './LearnersTab';
import { ChangesTab } from './ChangesTab';
import { ExperimentsTab } from './ExperimentsTab';
import { SettingsTab } from './SettingsTab';
import './learning.css';

const REFRESH = 30000;
const TABS = ['overview', 'learners', 'changes', 'experiments', 'settings'] as const;
const REVIEW: ProposalStatus[] = ['DRAFT', 'EVALUATED'];

/**
 * Learning: how the OMS learns from order outcomes (requirements doc §9.2 Learning console). Five tabs answer five
 * questions: is it helping (Overview), who is learning and how far may they go (Learners), what do they want to
 * change (Changes), what is being tried (Experiments), and what counts as a good outcome (Settings). Detail stays
 * behind "Show technical details" and the drawers.
 */
export default function LearningConsole() {
  const { principal } = useSession();
  const canRead = hasPermission(principal, 'learning.read');
  const canAdmin = hasPermission(principal, 'learning.admin');
  const canConfigure = canAdmin && hasPermission(principal, 'config.admin');
  const canApproveDecisions = hasPermission(principal, 'decision.approve');
  const [tab, setTab] = useQueryState('tab', 'overview');
  const qc = useQueryClient();

  const learners = useQuery({ queryKey: ['learning', 'learners'], queryFn: learningApi.learners, refetchInterval: REFRESH, enabled: canRead });
  const killSwitch = useQuery({ queryKey: ['learning', 'kill-switch'], queryFn: learningApi.killSwitch, refetchInterval: REFRESH, enabled: canRead });
  const review = useQueries({ queries: REVIEW.map((s) => ({ queryKey: ['learning', 'proposals', s], queryFn: () => learningApi.proposals(s), refetchInterval: REFRESH, enabled: canRead })) });
  const running = useQuery({ queryKey: ['learning', 'experiments', 'all'], queryFn: () => learningApi.experiments(true), refetchInterval: REFRESH, enabled: canRead });
  const resumeAll = useMutation({ mutationFn: () => learningApi.setAllLearning(true), onSuccess: () => { toast.ok('Learning resumed'); qc.invalidateQueries({ queryKey: ['learning'] }); } });
  const restartGlobal = useMutation({ mutationFn: () => learningApi.setGlobalLearning(true), onSuccess: () => { toast.ok('Learning restarted for every tenant'); qc.invalidateQueries({ queryKey: ['learning'] }); } });

  if (!canRead) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Learning</h1></div></div>
        <EmptyState title="You don't have access to this" hint="Ask an admin for the learning.read permission." />
      </div>
    );
  }

  const active = (TABS as readonly string[]).includes(tab) ? tab : 'overview';
  const toReview = review.reduce((n, q) => n + (q.data?.length ?? 0), 0);
  const ks = killSwitch.data;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1 className="row gap3"><span className="icon-badge ai" aria-hidden><IconSparkle size={18} /></span> Learning <Badge tone="ai">self-optimizing</Badge></h1>
          <div className="sub">The OMS learns from what happened to past orders, suggests changes, and only applies them the way you allow.</div>
        </div>
        <div className="learn-head-actions"><TechnicalToggle /></div>
      </div>

      {ks?.globalKillSwitch && (
        <div className="callout danger" role="status" data-testid="global-kill-switch">
          <span className="grow"><b>Learning is stopped for every tenant</b> {ks.globalSource === 'property' ? 'by server configuration' : `by ${ks.globalSetBy?.replace(/^user:/, '') ?? 'the platform operator'}`}{ks.globalSetAt ? ` ${relTime(ks.globalSetAt)}` : ''}. Nothing is promoted and live decisions use their standard values.</span>
          {ks.canSetGlobal && ks.globalSource !== 'property' && <Button size="sm" variant="primary" loading={restartGlobal.isPending} onClick={() => restartGlobal.mutate()}>Restart learning</Button>}
        </div>
      )}
      {ks?.tenantPaused && !ks.globalKillSwitch && (
        <div className="callout warn" role="status">
          <span className="grow"><b>All learning is paused.</b> Live decisions use their standard values until it is resumed.</span>
          {canAdmin && <Button size="sm" variant="primary" loading={resumeAll.isPending} onClick={() => resumeAll.mutate()}>Resume</Button>}
        </div>
      )}

      <Tabs ariaLabel="Learning sections" active={active} onChange={setTab} tabs={[
        { id: 'overview', label: 'Overview' },
        { id: 'learners', label: 'Learners', count: learners.data?.length },
        { id: 'changes', label: 'Changes', count: toReview || undefined },
        { id: 'experiments', label: 'Experiments', count: running.data?.filter((e) => e.status === 'RUNNING').length || undefined },
        { id: 'settings', label: 'Settings' },
      ]} />
      <TabPanel id="overview" active={active}><OverviewTab canAdmin={canAdmin} goTo={setTab} /></TabPanel>
      <TabPanel id="learners" active={active}><LearnersTab canAdmin={canAdmin} canConfigure={canConfigure} killSwitch={ks} /></TabPanel>
      <TabPanel id="changes" active={active}><ChangesTab canAdmin={canAdmin} learners={learners.data ?? []} /></TabPanel>
      <TabPanel id="experiments" active={active}><ExperimentsTab canAdmin={canAdmin} /></TabPanel>
      <TabPanel id="settings" active={active}><SettingsTab canAdmin={canAdmin} canApproveDecisions={canApproveDecisions} /></TabPanel>
    </div>
  );
}

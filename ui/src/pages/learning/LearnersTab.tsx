import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { AutonomyLevel, KillSwitchState, LearnerMetrics, LearnerView } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Select } from '../../components/Form';
import { ConfirmDialog, Dialog } from '../../components/Overlay';
import { EmptyState, ErrorState, Loading, Section } from '../../components/States';
import { fmtNum, fmtPct, relTime } from '../../lib/format';
import { toast } from '../../lib/toast';
import { InfoTip, Technical } from './Bits';
import { AUTONOMY, AUTONOMY_LEVELS, EVENT_DRIVEN, LEARNER_AREAS, areaOf, familyName, gateWords } from './copy';

const REFRESH = 30000;

/** Every learner, grouped by area, with how far it may act and whether it is running. */
export function LearnersTab({ canAdmin, canConfigure, killSwitch }: { canAdmin: boolean; canConfigure: boolean; killSwitch?: KillSwitchState }) {
  const qc = useQueryClient();
  const learners = useQuery({ queryKey: ['learning', 'learners'], queryFn: learningApi.learners, refetchInterval: REFRESH });
  const metrics = useQuery({ queryKey: ['learning', 'metrics'], queryFn: learningApi.metrics, refetchInterval: REFRESH });
  const [confirmPause, setConfirmPause] = useState(false);
  const [confirmGlobal, setConfirmGlobal] = useState(false);
  const paused = !!killSwitch?.tenantPaused;
  const globalOff = !!killSwitch?.globalKillSwitch;
  const global = useMutation({
    mutationFn: (enabled: boolean) => learningApi.setGlobalLearning(enabled),
    onSuccess: (s) => {
      setConfirmGlobal(false);
      toast[s.globalKillSwitch ? 'warn' : 'ok'](s.globalKillSwitch ? 'Learning stopped for every tenant' : 'Learning restarted for every tenant');
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
  });
  const all = useMutation({
    mutationFn: (enabled: boolean) => learningApi.setAllLearning(enabled),
    onSuccess: (s) => {
      setConfirmPause(false);
      toast[s.tenantPaused ? 'warn' : 'ok'](s.tenantPaused ? 'All learning paused' : 'Learning resumed', s.tenantPaused ? 'Live decisions use their standard values until you resume.' : undefined);
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
  });

  const byMetric = useMemo(() => new Map((metrics.data ?? []).map((m) => [m.learnerKey, m])), [metrics.data]);
  const groups = useMemo(() => {
    const out = new Map<string, LearnerView[]>();
    for (const a of [...LEARNER_AREAS.map((x) => x.title), 'Other']) out.set(a, []);
    for (const l of learners.data ?? []) out.get(areaOf(l.requirement))?.push(l);
    return [...out.entries()].filter(([, ls]) => ls.length > 0);
  }, [learners.data]);

  return (
    <div className="col gap4">
      <Section title="How far each learner may go"
        actions={canAdmin ? <>
          {paused
            ? <Button size="sm" variant="primary" loading={all.isPending} onClick={() => all.mutate(true)}>Resume all learning</Button>
            : <Button size="sm" variant="danger" onClick={() => setConfirmPause(true)}>Pause all learning</Button>}
          {killSwitch?.canSetGlobal && (globalOff
            ? <Button size="sm" variant="primary" loading={global.isPending} disabled={killSwitch.globalSource === 'property'}
                title={killSwitch.globalSource === 'property' ? 'Forced on by server configuration (oms.learning.kill-switch); change it there' : undefined}
                onClick={() => global.mutate(true)}>Restart learning for every tenant</Button>
            : <Button size="sm" variant="danger" onClick={() => setConfirmGlobal(true)}>Stop learning for every tenant</Button>)}
        </> : undefined}>
        <div className="autonomy-legend" aria-label="Autonomy levels">
          {AUTONOMY_LEVELS.map((lv) => <span key={lv}><b>{AUTONOMY[lv].label}</b> <InfoTip text={AUTONOMY[lv].explain} label={AUTONOMY[lv].label} /></span>)}
        </div>
        {!canConfigure && canAdmin && <p className="caption mt2">Changing how far a learner may go also needs the configuration admin permission.</p>}
      </Section>

      {learners.isLoading && <Loading />}
      {learners.isError && <ErrorState error={learners.error} retry={() => learners.refetch()} />}
      {learners.data && learners.data.length === 0 && <EmptyState title="No learners yet" hint="Learners register themselves when the service starts." />}

      {groups.map(([title, ls]) => {
        const area = LEARNER_AREAS.find((a) => a.title === title);
        // aria-labelledby takes space-separated ids, so the id must not contain spaces.
        const headingId = `area-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
        return (
          <section key={title} className="area" aria-labelledby={headingId}>
            <div className="area-title"><h3 id={headingId}>{title}</h3>{area && <span className="small muted">{area.caption}</span>}</div>
            {ls.map((l) => <LearnerRow key={l.learnerKey} learner={l} metrics={byMetric.get(l.learnerKey)} canAdmin={canAdmin} canConfigure={canConfigure} />)}
          </section>
        );
      })}

      <ConfirmDialog open={confirmPause} onClose={() => setConfirmPause(false)} onConfirm={() => all.mutate(false)} loading={all.isPending} danger
        title="Pause all learning?" confirmLabel="Pause all learning"
        body={<p className="small">Every learner stops, and live decisions go back to their standard values straight away. Nothing already learned is lost; you can resume at any time.</p>} />
      <ConfirmDialog open={confirmGlobal} onClose={() => setConfirmGlobal(false)} onConfirm={() => global.mutate(false)} loading={global.isPending} danger
        title="Stop learning for every tenant?" confirmLabel="Stop learning everywhere"
        body={<p className="small">This is the platform-wide kill switch. Every tenant's learners stop, nothing is promoted, and live decisions everywhere go back to their standard values until a platform administrator restarts learning.</p>} />
    </div>
  );
}

function LearnerRow({ learner: l, metrics, canAdmin, canConfigure }: { learner: LearnerView; metrics?: LearnerMetrics; canAdmin: boolean; canConfigure: boolean }) {
  const qc = useQueryClient();
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['learning', 'learners'] }); qc.invalidateQueries({ queryKey: ['learning', 'metrics'] }); qc.invalidateQueries({ queryKey: ['learning', 'kill-switch'] }); };
  const autonomy = useMutation({ mutationFn: (lv: AutonomyLevel) => learningApi.setAutonomy(l.learnerKey, lv), onSuccess: (v) => { toast.ok(`${v.displayName}: ${AUTONOMY[v.autonomyLevel].label.toLowerCase()}`); invalidate(); } });
  const toggle = useMutation({ mutationFn: (enabled: boolean) => learningApi.setLearnerEnabled(l.learnerKey, enabled), onSuccess: (_s, enabled) => { toast[enabled ? 'ok' : 'warn'](enabled ? `${l.displayName} switched on` : `${l.displayName} switched off`); invalidate(); } });
  const resume = useMutation({ mutationFn: () => learningApi.resume(l.learnerKey), onSuccess: () => { toast.ok(`${l.displayName} resumed`); invalidate(); } });
  const run = useMutation({ mutationFn: () => learningApi.runLearner(l.learnerKey), onSuccess: () => { toast.ok(`${l.displayName} ran`, 'Anything it found shows up under Changes.'); invalidate(); qc.invalidateQueries({ queryKey: ['learning', 'proposals'] }); } });

  const status = l.running ? { tone: 'ok', text: 'Running' } : l.paused ? { tone: 'warn', text: gateWords(l.gateReason) } : !l.enabled ? { tone: 'neutral', text: 'Switched off' } : { tone: 'warn', text: gateWords(l.gateReason) };
  const runnable = l.runnable ?? !EVENT_DRIVEN.has(l.learnerKey);

  return (
    <div className="learner-row" data-testid={`learner-${l.learnerKey}`}>
      <div className="col gap1" style={{ minWidth: 0 }}>
        <span className="name">{l.displayName}</span>
        <span className="summary">{l.summary}</span>
        <Technical>
          <span className="xs muted mono">{l.learnerKey} · {l.requirement}{l.outcomeDecisions.length ? ` · learns from ${l.outcomeDecisions.join(', ')}` : ''}{l.liveParameters.length ? ` · feeds ${l.liveParameters.join(', ')}` : ''} · gate {l.gateReason}</span>
        </Technical>
      </div>
      <div className="col gap1">
        <span><Badge tone={status.tone} dot className="wrap-text">{status.text}</Badge></span>
        <span className="xs muted">{l.lastRunAt ? `last ran ${relTime(l.lastRunAt)}` : runnable ? 'has not run yet' : 'updates as outcomes arrive'}</span>
        {metrics && (metrics.proposalsDrafted > 0 || metrics.coverage != null) && (
          <span className="xs muted">{metrics.proposalsDrafted > 0 ? `${fmtNum(metrics.proposalsDrafted)} suggested · ${fmtNum(metrics.proposalsPromoted)} live` : ''}{metrics.coverage != null ? `${metrics.proposalsDrafted > 0 ? ' · ' : ''}${fmtPct(metrics.coverage, 0)} outcomes` : ''}</span>
        )}
      </div>
      <div>
        <Select sm aria-label={`How far ${l.displayName} may go`} value={l.autonomyLevel} disabled={!canConfigure || autonomy.isPending}
          onChange={(e) => autonomy.mutate(e.target.value as AutonomyLevel)} title={AUTONOMY[l.autonomyLevel].explain}>
          {AUTONOMY_LEVELS.map((lv) => <option key={lv} value={lv}>{AUTONOMY[lv].label}</option>)}
        </Select>
        {l.liveParameters.length > 0 && (
          <div className="xs muted mt1">Can feed {l.liveParameters.length} live value{l.liveParameters.length === 1 ? '' : 's'} <InfoTip text={l.liveParameters.map(familyName).join(' · ')} label="Live values" /></div>
        )}
        <Budget learner={l} canConfigure={canConfigure} onSaved={invalidate} />
      </div>
      <div className="actions">
        {canAdmin && l.paused && <Button size="sm" variant="primary" loading={resume.isPending} onClick={() => resume.mutate()}>Resume</Button>}
        {canAdmin && runnable && l.running && <Button size="sm" variant="subtle" loading={run.isPending} onClick={() => run.mutate()} aria-label={`Run ${l.displayName} now`}>Run now</Button>}
        {canAdmin && <Button size="sm" variant={l.enabled ? 'ghost' : 'primary'} loading={toggle.isPending} onClick={() => toggle.mutate(!l.enabled)} aria-label={`${l.enabled ? 'Switch off' : 'Switch on'} ${l.displayName}`}>{l.enabled ? 'Switch off' : 'Switch on'}</Button>}
      </div>
    </div>
  );
}

/** GV-02: how far one change may move a number, and how often one target may change per week. */
function Budget({ learner: l, canConfigure, onSaved }: { learner: LearnerView; canConfigure: boolean; onSaved: () => void }) {
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState('');
  const [perWeek, setPerWeek] = useState('');
  const stepNum = step.trim() === '' ? null : Number(step);
  const weekNum = Number(perWeek);
  const invalid = (stepNum !== null && !(stepNum > 0)) || !Number.isInteger(weekNum) || weekNum < 0;
  const save = useMutation({
    mutationFn: () => learningApi.setBudget(l.learnerKey, stepNum === null ? { clearMaxStep: true, maxPromotionsPerWeek: weekNum } : { maxStep: stepNum, maxPromotionsPerWeek: weekNum }),
    onSuccess: (v) => { toast.ok(`${v.displayName}: limits saved`); setOpen(false); onSaved(); },
  });
  const show = () => { setStep(l.maxStep == null ? '' : String(l.maxStep)); setPerWeek(String(l.maxPromotionsPerWeek)); setOpen(true); };
  const summary = `${l.maxStep == null ? 'Standard step' : `Moves at most ${l.maxStep}`} · ${l.maxPromotionsPerWeek} change${l.maxPromotionsPerWeek === 1 ? '' : 's'} a week per target`;
  return (
    <div className="xs muted mt1" data-testid={`budget-${l.learnerKey}`}>
      {summary} <InfoTip text="A change bigger than the step, or more changes than this per policy in a week, needs a person to give a written reason before it goes live." label="Change limits" />
      {canConfigure && <> · <Button size="sm" variant="ghost" onClick={show} aria-label={`Change limits for ${l.displayName}`}>Change limits</Button></>}
      <Dialog open={open} onClose={() => setOpen(false)} title={`Change limits for ${l.displayName}`}
        description="Changes within these limits can go live without a written reason; anything bigger waits for a person."
        footer={<><Button onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" disabled={invalid} loading={save.isPending} onClick={() => save.mutate()}>Save limits</Button></>}>
        <Input label="Largest move per change" type="number" step="any" min="0" value={step} onChange={(e) => setStep(e.target.value)}
          hint="Leave empty for the standard limit (0.05 for values between 0 and 1)." error={stepNum !== null && !(stepNum > 0) ? 'Must be more than 0' : undefined} />
        <Input label="Changes per policy per week" type="number" step="1" min="0" value={perWeek} onChange={(e) => setPerWeek(e.target.value)}
          error={!Number.isInteger(weekNum) || weekNum < 0 ? 'A whole number, 0 or more' : undefined} />
      </Dialog>
    </div>
  );
}

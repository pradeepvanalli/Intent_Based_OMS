import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { autonomyApi } from '../../api/autonomy';
import type { AutonomyActionType, AutonomyGuardrails } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { Input, Switch } from '../../components/Form';
import { fmtUsd, humanize } from '../../lib/format';
import { toast } from '../../lib/toast';

const ALL_ACTIONS: AutonomyActionType[] = ['re_source', 're_promise', 'retry_payment', 'release_hold', 'notify_customer'];

/**
 * Agentic Control Tower guardrails: kill switch, daily action cap with a progress bar, per-action $ cap,
 * toggleable allowed-action chips and a confidence override. Read-only for anyone without config.admin.
 */
export function GuardrailsPanel({ guardrails, actionsToday, canEdit }: { guardrails: AutonomyGuardrails; actionsToday: number; canEdit: boolean }) {
  const qc = useQueryClient();
  const [maxActions, setMaxActions] = useState(String(guardrails.maxActionsPerDay));
  const [maxUsd, setMaxUsd] = useState(String(guardrails.maxUsdImpactPerAction));
  const [minConf, setMinConf] = useState(guardrails.minConfidenceOverride == null ? '' : String(guardrails.minConfidenceOverride));

  const invalidate = () => qc.invalidateQueries({ queryKey: ['autonomy', 'guardrails'] });
  const update = useMutation({
    mutationFn: autonomyApi.updateGuardrails,
    onSuccess: () => { invalidate(); toast.ok('Guardrails updated'); },
  });
  const kill = useMutation({
    mutationFn: autonomyApi.killSwitch,
    onSuccess: (g) => { invalidate(); toast[g.killSwitch ? 'warn' : 'ok'](g.killSwitch ? 'Kill switch engaged' : 'Kill switch released', g.killSwitch ? 'Every autonomous action is stopped until this is turned off.' : 'Autonomous actions may run again, subject to the other guardrails.'); },
  });

  const toggleKill = (next: boolean) => {
    if (next && typeof window !== 'undefined' && !window.confirm('Engage the autonomy kill switch?\n\nThis immediately stops ALL autonomous actions for this tenant — every diagnosis will escalate to a human instead — until you switch it off again.')) return;
    kill.mutate(next);
  };

  const toggleAction = (type: AutonomyActionType) => {
    if (!canEdit) return;
    const next = guardrails.allowedActions.includes(type) ? guardrails.allowedActions.filter((a) => a !== type) : [...guardrails.allowedActions, type];
    update.mutate({ allowedActions: next });
  };

  const pct = guardrails.maxActionsPerDay > 0 ? Math.min(100, Math.round((actionsToday / guardrails.maxActionsPerDay) * 100)) : 0;

  return (
    <div className="card glow-ring active" style={{ marginTop: 'var(--s4)' }}>
      <div className="card-head"><h2>Guardrails</h2><span className="xs muted">What autonomy is allowed to do, and the instant stop</span></div>
      <div className="card-body col gap4">
        <div className="row gap3" style={{ padding: 'var(--s3)', borderRadius: 'var(--r2)', border: `2px solid var(--danger)`, background: guardrails.killSwitch ? 'var(--danger-soft)' : 'transparent' }} role="group" aria-label="Autonomy kill switch">
          <div className="col gap1">
            <strong className="row gap2">Kill switch {guardrails.killSwitch && <Badge tone="danger" dot>engaged</Badge>}</strong>
            <span className="xs muted">Stops every autonomous action immediately; human escalation keeps working. Independent of the master switch below.</span>
          </div>
          <span className="grow" />
          <Switch label={guardrails.killSwitch ? 'Engaged' : 'Off'} checked={guardrails.killSwitch} disabled={!canEdit || kill.isPending} onChange={toggleKill} />
        </div>

        <div className="row gap3" aria-label="Autonomy master switch">
          <span className="small">Autonomy enabled for this tenant</span>
          <span className="grow" />
          <Switch label={guardrails.enabled ? 'On' : 'Off'} checked={guardrails.enabled} disabled={!canEdit} onChange={(v) => update.mutate({ enabled: v })} />
        </div>

        <div className="col gap2">
          <div className="row-between small"><span>Max autonomous actions / day</span><span className="mono">{actionsToday} / {guardrails.maxActionsPerDay}</span></div>
          <div className="bar" style={{ width: '100%' }} role="meter" aria-valuemin={0} aria-valuemax={guardrails.maxActionsPerDay} aria-valuenow={actionsToday} aria-label="Autonomous actions used today">
            <span style={{ width: `${pct}%`, background: pct >= 100 ? 'var(--danger)' : undefined }} />
          </div>
          {canEdit && <form className="row gap2" onSubmit={(e) => { e.preventDefault(); update.mutate({ maxActionsPerDay: Number(maxActions) }); }}>
            <Input sm type="number" min={0} aria-label="Max actions per day" value={maxActions} onChange={(e) => setMaxActions(e.target.value)} style={{ width: 90 }} />
            <Button type="submit" size="sm" disabled={update.isPending}>Save</Button>
          </form>}
        </div>

        <div className="col gap2">
          <div className="row-between small"><span>$ impact cap per action</span><span className="mono">{fmtUsd(guardrails.maxUsdImpactPerAction)}</span></div>
          {canEdit && <form className="row gap2" onSubmit={(e) => { e.preventDefault(); update.mutate({ maxUsdImpactPerAction: Number(maxUsd) }); }}>
            <Input sm type="number" min={0} step="0.01" aria-label="Max USD impact per action" value={maxUsd} onChange={(e) => setMaxUsd(e.target.value)} style={{ width: 110 }} />
            <Button type="submit" size="sm" disabled={update.isPending}>Save</Button>
          </form>}
        </div>

        <div className="col gap2">
          <span className="small">Allowed actions</span>
          <div className="pill-row" role="group" aria-label="Allowed autonomous action types">
            {ALL_ACTIONS.map((t) => {
              const on = guardrails.allowedActions.includes(t);
              return <button key={t} type="button" className={`chip chip-btn ${on ? 'badge-ai' : ''}`} aria-pressed={on} disabled={!canEdit}
                title={on ? `${t} is allowed autonomously` : `${t} is not allowed autonomously`} onClick={() => toggleAction(t)}>{on ? '✓ ' : ''}{humanize(t)}</button>;
            })}
          </div>
          <span className="xs muted">refund and cancel are never offered here — they are never autonomous, regardless of configuration.</span>
        </div>

        <div className="col gap2">
          <div className="row-between small"><span>Minimum confidence override</span><span className="mono">{guardrails.minConfidenceOverride == null ? 'using the decision’s own threshold' : guardrails.minConfidenceOverride}</span></div>
          {canEdit && <form className="row gap2" onSubmit={(e) => { e.preventDefault(); update.mutate({ minConfidenceOverride: minConf === '' ? null : Number(minConf) }); }}>
            <Input sm type="number" min={0} max={1} step="0.01" placeholder="e.g. 0.95" aria-label="Minimum confidence override" value={minConf} onChange={(e) => setMinConf(e.target.value)} style={{ width: 90 }} />
            <Button type="submit" size="sm" disabled={update.isPending}>Save</Button>
            {minConf !== '' && <Button type="button" variant="ghost" size="sm" onClick={() => { setMinConf(''); update.mutate({ minConfidenceOverride: null }); }}>Clear</Button>}
          </form>}
          <span className="xs muted">Raises, never lowers, the decision's own act threshold.</span>
        </div>
      </div>
    </div>
  );
}

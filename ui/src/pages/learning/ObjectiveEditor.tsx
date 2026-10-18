import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { learningApi } from '../../api/learning';
import type { ObjectiveProfile, ObjectiveWeights } from '../../api/types';
import { Button } from '../../components/Button';
import { Range } from '../../components/Form';
import { toast } from '../../lib/toast';
import { fmtDateTime } from '../../lib/format';

const TERMS: { key: keyof ObjectiveWeights; label: string; hint: string }[] = [
  { key: 'fill', label: 'Fill rate', hint: 'Units shipped vs. ordered' },
  { key: 'otd', label: 'On-time delivery', hint: 'Deliveries on or before promise' },
  { key: 'cost', label: 'Cost to serve', hint: 'Fulfilment + shipping + handling per order' },
  { key: 'split', label: 'Splits', hint: 'Penalty for an avoidable split shipment' },
  { key: 'fraud', label: 'Fraud and abuse loss', hint: 'Chargebacks, refused deliveries, abusive returns' },
  { key: 'cx', label: 'Customer contacts', hint: 'Penalty for a WISMO or complaint contact' },
  { key: 'inv', label: 'Aged-stock relief', hint: 'Reward for selling through stock that was aging' },
];
const PRESETS: { key: string; label: string }[] = [{ key: 'balanced', label: 'Balanced' }, { key: 'margin_first', label: 'Margin first' }, { key: 'speed_first', label: 'Speed first' }];

/**
 * XP-07: the tenant objective editor — R(e) = fill·FillRate + otd·OnTime − cost·(Cost/OrderValue) − split·Splits
 * − fraud·FraudLoss − cx·Contacts + inv·AgedStockRelief (requirements doc §7). A structured weights editor with
 * three presets; there's no free-text "describe your priorities" endpoint on the backend yet, so this doesn't
 * fake one — that's a real gap for a future build, not something to paper over here.
 */
export function ObjectiveEditor({ profile, canEdit }: { profile: ObjectiveProfile; canEdit: boolean }) {
  const qc = useQueryClient();
  const [weights, setWeights] = useState<ObjectiveWeights>(profile.weights);
  useEffect(() => setWeights(profile.weights), [profile.weights]);

  const save = useMutation({
    mutationFn: () => learningApi.setObjective(weights),
    onSuccess: (p) => { qc.setQueryData(['learning', 'objective'], p); toast.ok('Objective updated', `Now live as version ${p.version}.`); },
  });

  const dirty = TERMS.some((t) => weights[t.key] !== profile.weights[t.key]);
  const applyPreset = (name: string) => {
    const presets: Record<string, ObjectiveWeights> = {
      balanced: { fill: 1, otd: 1, cost: 1, split: 1, fraud: 1, cx: 1, inv: 1 },
      margin_first: { fill: 0.8, otd: 1.0, cost: 1.6, split: 0.9, fraud: 1.3, cx: 0.6, inv: 0.7 },
      speed_first: { fill: 0.9, otd: 1.6, cost: 0.8, split: 0.5, fraud: 1.0, cx: 0.6, inv: 0.5 },
    };
    setWeights(presets[name] ?? presets.balanced);
  };

  return (
    <div className="col gap3">
      <div className="row-between">
        <span className="small muted">Live objective v{profile.version}{profile.approvedAt && <> · approved {fmtDateTime(profile.approvedAt)}</>}</span>
        {canEdit && (
          <div className="row gap2">
            {PRESETS.map((p) => <button key={p.key} type="button" className="chip chip-btn" onClick={() => applyPreset(p.key)}>{p.label}</button>)}
          </div>
        )}
      </div>
      <div className="grid grid-2 gap3">
        {TERMS.map((t) => (
          <Range key={t.key} label={<span>{t.label} <span className="xs muted">· {t.hint}</span></span>} value={weights[t.key]} min={0} max={2} step={0.05}
            format={(v) => v.toFixed(2)} onChange={(v) => setWeights((w) => ({ ...w, [t.key]: v }))} hint={undefined} />
        ))}
      </div>
      {canEdit ? (
        <div className="row gap2">
          <Button variant="primary" size="sm" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate()}>Save as new version</Button>
          {dirty && <Button variant="ghost" size="sm" onClick={() => setWeights(profile.weights)}>Reset</Button>}
        </div>
      ) : <span className="xs muted">Needs learning.admin to change.</span>}
      <span className="xs muted">Every term is normalised to [0,1] against this tenant's own replay baseline before these weights apply, so a weight of 1 means the same thing across every tenant. Hard constraints — margin floor, compliance holds, refund/cancel never autonomous — are never traded for reward, regardless of these weights.</span>
    </div>
  );
}

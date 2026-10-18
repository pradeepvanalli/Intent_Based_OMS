import type { DecisionTraceLearning } from '../../api/types';
import { fmtConf, humanize, valueLabel } from '../../lib/format';
import { useTechnical } from '../../lib/technical';
import { factName, servedWhy, subjectName } from '../../pages/learning/copy';
import { Badge } from '../Badge';

/**
 * XP-03: how the self-optimizing layer shaped this one decision, from what the runtime recorded on it (never a
 * re-lookup of today's values): learned facts read and whether each was used, calibration, experiment arm and
 * provider routing. Renders nothing when learning played no part.
 */
export function LearningSection({ learning }: { learning?: DecisionTraceLearning | null }) {
  const [technical] = useTechnical();
  if (!learning) return null;
  const facts = learning.learnedFacts ?? [];
  const hasAnything = facts.length > 0 || learning.rawConfidence !== undefined || !!learning.experiment || !!learning.providerRouted || (learning.arms?.length ?? 0) > 1;
  if (!hasAnything) return null;
  const used = facts.filter((f) => f.source === 'LEARNED').length;
  return (
    <section className="learning-section" aria-labelledby="why-learning">
      <div className="section-title" id="why-learning">How learning shaped this decision</div>
      <p className="small" style={{ margin: '0 0 var(--s2)' }}>
        {facts.length === 0 ? 'No learned values were read.' : used === 0
          ? `It read ${facts.length} learned value${facts.length === 1 ? '' : 's'} but used the standard values.`
          : `It used ${used} of ${facts.length} learned value${facts.length === 1 ? '' : 's'} it read.`}
      </p>
      {facts.length > 0 && (
        <div role="list" aria-label="Learned values read">
          {facts.map((f, i) => (
            <div role="listitem" key={`${f.subjectId}-${f.factKey}-${i}`} className="fact-row">
              <span><b>{subjectName(f.subjectType)} {f.subjectId}</b>: {factName(f.factKey)}{technical && f.version !== undefined && <span className="xs muted mono"> · v{f.version}</span>}</span>
              <span className="row gap2 wrap">
                <Badge tone={f.source === 'LEARNED' ? 'ai' : 'outline'} className="wrap-text">{servedWhy(f.reason, f.source)}</Badge>
                <span className="xs muted num">learned {valueLabel(f.learned)}{f.source === 'LEARNED' && f.served !== f.learned ? ` · used ${valueLabel(f.served)}` : ''}</span>
              </span>
            </div>
          ))}
        </div>
      )}
      <ul className="small mt2" style={{ margin: 0, paddingLeft: 18 }}>
        {learning.rawConfidence !== undefined && <li>The model said it was {fmtConf(learning.rawConfidence)} sure; corrected for how often it is right, that became {fmtConf(learning.calibratedConfidence)}.</li>}
        {learning.experiment && <li>Part of an experiment: this customer is in the <b>{humanize(learning.experiment.arm ?? 'test')}</b> group{learning.experiment.version ? ` and saw version ${learning.experiment.version}` : ''}.</li>}
        {learning.providerRouted && <li>Asked <b>{learning.providerRouted}</b> first, because it proved just as accurate for this decision at lower cost.</li>}
        {learning.shadow?.arm && <li>A trial policy would have chosen <b>{humanize(learning.shadow.arm)}</b>; it was recorded but not used.</li>}
      </ul>
      {technical && (learning.arms?.length ?? 0) > 0 && (
        <div className="xs muted mono mt2">options {learning.arms!.join(', ')} · chose {learning.chosenArm ?? '—'} · probability {learning.propensity == null ? '—' : learning.propensity.toFixed(3)} · policy v{learning.policyVersion ?? '—'}</div>
      )}
    </section>
  );
}

/** XP-05: what may be said to a customer about learned context, in plain sentences chosen by the server. */
export function CustomerLearnedContext({ sentences }: { sentences?: string[] }) {
  if (!sentences || sentences.length === 0) return null;
  return (
    <section>
      <div className="section-title">What we know from past orders</div>
      <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>{sentences.map((s, i) => <li key={i}>{s}</li>)}</ul>
    </section>
  );
}

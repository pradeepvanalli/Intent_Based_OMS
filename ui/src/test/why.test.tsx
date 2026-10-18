import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { WhyBody } from '../components/why/WhyDrawer';
import type { DecisionTrace } from '../api/types';
import { loginAsAdmin, renderWithProviders } from './utils';

const trace: DecisionTrace = {
  record: { id: 'dr_1', decision: 'fulfilment_intent', version: 7, subjectType: 'order', subjectId: 'ORD-1', mode: 'LIVE', layer: 'L1_SYSTEM_ONE', band: 'ACT', value: 'ship_complete', confidence: 0.91, action: 'act', provider: 'jev', modelVersion: 'jev-s1', latencyMs: 212, costUsd: 0.00004, evaluatedAt: new Date().toISOString(), actor: 'system:workflow' },
  definition: { name: 'fulfilment_intent', version: 7, tier: 'T1', owner: 'omnichannel-strategy', bands: { actGte: 0.8, confirmGte: 0.5 } },
  provider: 'jev', modelVersion: 'jev-s1', latencyMs: 212, costUsd: 0.00004, inputTokens: 900, outputTokens: 40,
  layers: [{ layer: 'L0_RULE', ran: true, outcome: 'no rule matched' }, { layer: 'L1_SYSTEM_ONE', ran: true, outcome: 'jev answered' }, { layer: 'L2_LLM', ran: false }, { layer: 'L3_HUMAN', ran: false }],
  inputs: { order: { channel: 'web', notes: ['gift'] }, customer: { name: 'tok_8a1', tier: 'gold' } }, tokenisedFields: ['customer.name'],
  questions: [{ key: 'intent', type: 'choice', instructions: 'Which fulfilment outcome does the buyer most want?', criteria: { ship_complete: 'Everything together', unclear: 'Not enough information' }, answer: { type: 'choice', value: 'ship_complete', probabilities: { ship_complete: 0.91, unclear: 0.09 }, confidence: 0.91 } }],
  rules: [{ id: 'explicit_choice', when: 'order.intent_choices.size() == 1', then: 'order.intent_choices[0]', matched: false }],
  checks: [{ expression: 'chosen_plan.margin >= tenant.margin_floor', passed: true, phase: 'post' }], band: 'ACT', action: 'act', actor: 'system:workflow',
};

describe('WhyBody', () => {
  it('renders outcome, layer path, questions, rules, checks and inputs', () => {
    loginAsAdmin();
    renderWithProviders(<WhyBody trace={trace} customer={false} />);
    expect(screen.getByText('ship_complete', { selector: 'span.mono' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Layer path' })).toBeInTheDocument();
    expect(screen.getByText(/Which fulfilment outcome/)).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'ship_complete probability' })).toHaveAttribute('aria-valuenow', '0.91');
    expect(screen.getByText('explicit_choice')).toBeInTheDocument();
    expect(screen.getByText('chosen_plan.margin >= tenant.margin_floor')).toBeInTheDocument();
    expect(screen.getByText('Record override')).toBeInTheDocument();
  });
  it('explains in plain words how learning shaped the decision, and which learned values were not used', () => {
    loginAsAdmin();
    renderWithProviders(<WhyBody trace={{ ...trace, learning: {
      learnedFacts: [
        { subjectType: 'NODE', subjectId: 'DC-NV', factKey: 'NODE_REJECT_RATE', version: 14, learned: 0.031, served: 0.031, source: 'LEARNED', reason: 'promoted for live use' },
        { subjectType: 'NODE', subjectId: 'ST-CHI-01', factKey: 'NODE_ON_TIME_RATE', version: 9, learned: 0.71, served: null, source: 'STATIC', reason: 'not promoted for live use' },
      ], learnedFactsServed: 1, rawConfidence: 0.95, calibratedConfidence: 0.88, experiment: { experimentId: 'exp_1', arm: 'canary', version: 4 }, usedLearning: true,
    } }} customer={false} />);
    const section = screen.getByRole('region', { name: 'How learning shaped this decision' });
    expect(within(section).getByText('It used 1 of 2 learned values it read.')).toBeInTheDocument();
    expect(within(section).getByText('not used: not switched on for live decisions yet')).toBeInTheDocument();
    expect(within(section).getByText(/0.95 sure; corrected for how often it is right, that became 0.88/)).toBeInTheDocument();
    expect(within(section).getByText('canary')).toBeInTheDocument();
    expect(within(section).queryByText('v14', { exact: false })).not.toBeInTheDocument(); // versions only with technical details on
  });
  it('shows only server-chosen learned sentences in the customer-safe view', () => {
    renderWithProviders(<WhyBody trace={{ ...trace, audience: 'customer', learning: { learnedFacts: [{ subjectType: 'NODE', subjectId: 'DC-NV', factKey: 'NODE_REJECT_RATE', source: 'LEARNED' }], learnedFactsServed: 1, usedLearning: true }, learnedContext: ['We picked the location that ships on time most often.'] }} customer />);
    expect(screen.getByText('We picked the location that ships on time most often.')).toBeInTheDocument();
    expect(screen.queryByText('How learning shaped this decision')).not.toBeInTheDocument();
  });
  it('hides internal sections in customer-safe view', () => {
    renderWithProviders(<WhyBody trace={{ ...trace, audience: 'customer', customerSafeExplanation: 'We chose ship complete.' }} customer />);
    expect(screen.getByText('We chose ship complete.')).toBeInTheDocument();
    expect(screen.queryByText('explicit_choice')).not.toBeInTheDocument();
    expect(screen.queryByText('Record override')).not.toBeInTheDocument();
  });
});

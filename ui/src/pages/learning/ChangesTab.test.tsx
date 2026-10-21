import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ChangeRow } from './ChangesTab';
import type { Proposal } from '../../api/types';

// D5-5: the Changes-list row never read p.criticReview, so a proposal the automatic critic flagged RISKY (or
// NEEDS_CHANGES) showed no indication in the list -- only the drawer (opened separately) showed it. ChangeRow must
// now badge a RISKY/NEEDS_CHANGES verdict on the row itself, and must not for a LOOKS_SAFE one or no review at all.
function proposal(overrides: Partial<Proposal>): Proposal {
  return {
    proposalId: 'prop_test1',
    changeset: {
      target: { type: 'decision', name: 'sourcing_node_score' },
      baseVersion: 1,
      changes: [{ type: 'ThresholdChange', decision: 'sourcing_node_score', band: 'act', from: 0.8, to: 0.78 }],
      tier: 'T1',
    },
    tier: 'T1',
    status: 'EVALUATED',
    createdBy: 'system:band_threshold',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    evaluationReport: { liftEstimate: 0.02, confidenceInterval: { lower: 0.001, upper: 0.04 } },
    ...overrides,
  };
}

describe('ChangeRow', () => {
  it('badges a RISKY critic verdict on the row itself', () => {
    const p = proposal({ criticReview: { verdict: 'RISKY', risks: ['Escalations would rise sharply'], source: 'llm' } });
    render(<ChangeRow p={p} names={new Map()} onOpen={() => {}} />);
    const badge = screen.getByTestId('critic-flag');
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toContain('Risky');
  });

  it('badges a NEEDS_CHANGES critic verdict on the row itself', () => {
    const p = proposal({ criticReview: { verdict: 'NEEDS_CHANGES', risks: ['No counterfactual estimate'], source: 'rules' } });
    render(<ChangeRow p={p} names={new Map()} onOpen={() => {}} />);
    const badge = screen.getByTestId('critic-flag');
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toContain('Needs changes');
  });

  it('shows no critic-flag badge for a LOOKS_SAFE verdict', () => {
    const p = proposal({ criticReview: { verdict: 'LOOKS_SAFE', risks: [], source: 'rules' } });
    render(<ChangeRow p={p} names={new Map()} onOpen={() => {}} />);
    expect(screen.queryByTestId('critic-flag')).not.toBeInTheDocument();
  });

  it('shows no critic-flag badge when there is no critic review yet', () => {
    const p = proposal({ criticReview: null });
    render(<ChangeRow p={p} names={new Map()} onOpen={() => {}} />);
    expect(screen.queryByTestId('critic-flag')).not.toBeInTheDocument();
  });
});

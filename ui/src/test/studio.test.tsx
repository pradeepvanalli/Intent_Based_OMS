import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { StructuredEditor } from '../pages/studio/StructuredEditor';
import { decisionToYaml, parseDecisionYaml, toForm } from '../lib/modelYaml';
import { DECISION_YAML } from '../api/mock/samples';
import { renderWithProviders } from './utils';

describe('modelYaml round-trip', () => {
  it('parses Appendix B YAML and serialises it back without losing structure', () => {
    const parsed = parseDecisionYaml(DECISION_YAML.fulfilment_intent);
    expect(parsed.name).toBe('fulfilment_intent');
    expect(parsed.questions?.intent.choiceCriteria?.unclear).toMatch(/Not enough/);
    expect(parsed.bands).toEqual({ actGte: 0.8, confirmGte: 0.5 });
    const yaml = decisionToYaml(toForm(parsed));
    const again = parseDecisionYaml(yaml);
    expect(again.questions).toEqual(parsed.questions);
    expect(again.rules).toEqual(parsed.rules);
    expect(again.stateInclude).toEqual(parsed.stateInclude);
    expect(again.postChecks).toEqual(parsed.postChecks);
  });
  it('rejects documents without a decision name', () => {
    expect(() => parseDecisionYaml('version: 1')).toThrow(/decision/);
  });
});

describe('StructuredEditor', () => {
  it('renders questions with escape highlighting and propagates edits', () => {
    const form = toForm(parseDecisionYaml(DECISION_YAML.fulfilment_intent));
    const onChange = vi.fn();
    renderWithProviders(<StructuredEditor form={form} onChange={onChange} />);
    expect(screen.getAllByText('choice').length).toBeGreaterThan(0);
    expect(screen.getByDisplayValue('unclear')).toBeInTheDocument();
    expect(screen.queryByText('missing escape option')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Owner (team)'), { target: { value: 'ops' } });
    expect(onChange).toHaveBeenCalled();
    expect(onChange.mock.calls[0][0].owner).toBe('ops');
  });
  it('flags a choice without an escape option', () => {
    const form = toForm(parseDecisionYaml(DECISION_YAML.fulfilment_intent));
    delete form.questions.intent.choiceCriteria!.unclear;
    renderWithProviders(<StructuredEditor form={form} onChange={() => {}} />);
    expect(screen.getByText('missing escape option')).toBeInTheDocument();
  });
});

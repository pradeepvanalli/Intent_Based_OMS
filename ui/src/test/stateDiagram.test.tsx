import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { StateDiagram } from '../pages/stateModels/StateDiagram';
import { parseStateModelYaml } from '../lib/modelYaml';
import { LINE_MODEL_V3 } from '../api/mock/samples';
import { layoutGraph } from '../lib/layout';
import { lineDiff } from '../lib/diff';
import { renderWithProviders } from './utils';

describe('state diagram', () => {
  it('lays out every state on a layer with terminal states last', () => {
    const m = parseStateModelYaml(LINE_MODEL_V3);
    const layout = layoutGraph(m.states.map((s) => ({ id: s.id, terminal: s.meta === 'TERMINAL', initial: s.meta === 'CREATED' })), m.transitions.flatMap((t) => t.from.map((f) => ({ id: `${t.id}@${f}`, from: f, to: t.to, label: t.trigger }))));
    expect(layout.nodes).toHaveLength(m.states.length);
    const maxLayer = Math.max(...layout.nodes.map((n) => n.layer));
    expect(layout.nodes.find((n) => n.id === 'delivered')!.layer).toBe(maxLayer);
    expect(layout.nodes.find((n) => n.id === 'created')!.layer).toBe(0);
    expect(layout.edges.some((e) => e.back)).toBe(true);
  });
  it('renders an SVG with one labelled node per state', () => {
    const m = parseStateModelYaml(LINE_MODEL_V3);
    renderWithProviders(<StateDiagram model={m} />);
    expect(screen.getByRole('img', { name: /State diagram with 9 states/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^delivered \(terminal\)/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /released.*SLA 4h/ })).toBeInTheDocument();
  });
});
describe('lineDiff', () => {
  it('marks added, removed and changed lines', () => {
    const rows = lineDiff('a\nb\nc', 'a\nB\nc\nd');
    expect(rows.map((r) => r.kind)).toEqual(['same', 'chg', 'same', 'add']);
  });
});

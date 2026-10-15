import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { loginAsAdmin, renderWithProviders } from './utils';
import { setSession } from '../auth/session';
import Copilot from '../pages/copilot/Copilot';
import Bootstrap from '../pages/copilot/Bootstrap';
import { OpsCopilot } from '../pages/controlTower/OpsCopilot';
import { CsDraftTab } from '../pages/orders/CsDraftTab';
import { MarginGuardian } from '../pages/analytics/MarginGuardian';
import { WhyDrawer } from '../components/why/WhyDrawer';
import { DiffViewer } from '../components/DiffViewer';
import { Toasts } from '../components/Toasts';
import { unifiedDiff } from '../lib/diff';
import { mockRequest } from '../api/mock';
import type { ChangeSet, DecisionRecord } from '../api/types';

vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), MOCK: true }));

const T = 15000;

describe('unifiedDiff', () => {
  it('emits hunks with +/- lines and context', () => {
    const out = unifiedDiff('a\nb\nc\nd\ne\nf\ng\nh\n', 'a\nb\nc\nX\ne\nf\ng\nh\n', 'before', 'after');
    expect(out).toContain('--- before');
    expect(out).toContain('@@ -1,7 +1,7 @@');
    expect(out).toContain('-d');
    expect(out).toContain('+X');
  });
});

describe('DiffViewer', () => {
  it('switches between side-by-side, unified and result views', () => {
    renderWithProviders(<DiffViewer before={'version: 1\nname: x\n'} after={'version: 2\nname: x\n'} leftLabel="v1" rightLabel="v2" />);
    expect(screen.getByLabelText('Unified diff')).toHaveTextContent('+version: 2');
    fireEvent.click(screen.getByRole('button', { name: 'Side by side' }));
    expect(screen.getByRole('table', { name: 'Diff v1 to v2' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Unified' }));
    fireEvent.click(screen.getByRole('button', { name: 'Result' }));
    expect(screen.queryByLabelText('Unified diff')).not.toBeInTheDocument();
  });
});

describe('Copilot page', () => {
  it('shows status, examples and history for an editor; proposes a change set and stages it', async () => {
    loginAsAdmin();
    renderWithProviders(<Routes><Route path="/copilot" element={<Copilot />} /></Routes>, { route: '/copilot' });
    expect(await screen.findByRole('status', { name: /copilot available/ }, { timeout: T })).toBeInTheDocument();
    expect(screen.getByText('Gold customers with a receipt get an instant refund under $250')).toBeInTheDocument();
    await screen.findAllByText(/instant refund/i, {}, { timeout: T });
    fireEvent.click(screen.getByText('Never split orders containing hazmat items'));
    fireEvent.click(screen.getByRole('button', { name: 'Propose changes' }));
    const list = await screen.findByRole('list', { name: 'Proposed changes' }, { timeout: T });
    const cards = within(list).getAllByRole('listitem');
    expect(cards.length).toBeGreaterThanOrEqual(3);
    expect(within(list).getAllByLabelText(/kind compliance rule/).length).toBeGreaterThan(0);
    expect(within(list).getAllByLabelText(/kind state model/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Clarifying questions/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Stage selected as drafts/ }));
    await screen.findByText(/changes staged\./, {}, { timeout: T });
    expect(screen.getAllByText(/Artifact art_/).length).toBeGreaterThan(0);
  }, T * 2);

  it('explains a change and adds its tests to the golden set', async () => {
    loginAsAdmin();
    const cs = await mockRequest<ChangeSet>('POST', 'http://mock.local/api/v1/copilot/changes', { text: 'Raise the margin floor for marketplace orders to 15%', scope: 'policies' });
    renderWithProviders(<><Routes><Route path="/copilot" element={<Copilot />} /></Routes><Toasts /></>, { route: `/copilot?cs=${cs.id}` });
    const list = await screen.findByRole('list', { name: 'Proposed changes' }, { timeout: T });
    expect(within(list).getByText(/valid · 1 warning/)).toBeInTheDocument();
    fireEvent.click(within(list).getAllByRole('button', { name: 'Explain' })[0]);
    await within(list).findByText(/This change changes a value read at run time/, {}, { timeout: T });
    fireEvent.click(within(list).getAllByRole('button', { name: 'Add tests to golden set' })[0]);
    await screen.findByText(/tests? added/, {}, { timeout: T });
  }, T * 2);

  it('lists artefacts with apply gated by permission', async () => {
    setSession('t', { type: 'USER', id: 'strategy', tenantId: 'demo', roles: ['strategy_lead'], permissions: ['decision.*', 'statemodel.*', 'analytics.read'], trustTier: 3 });
    renderWithProviders(<Routes><Route path="/copilot" element={<Copilot />} /></Routes>, { route: '/copilot?tab=artifacts' });
    const table = await screen.findByRole('table', { name: 'Staged artefacts' }, { timeout: T });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.length).toBeGreaterThan(0);
    const cfgRow = rows.find((r) => within(r).queryByLabelText(/kind tenant config/));
    expect(cfgRow).toBeTruthy();
    fireEvent.click(cfgRow!);
    const apply = await screen.findByRole('button', { name: /Apply/ }, { timeout: T });
    expect(apply).toBeDisabled();
    expect(screen.getByText(/needs/)).toHaveTextContent('config.admin');
  }, T * 2);

  it('shows the offline banner and disables the composer when the LLM is unavailable', async () => {
    loginAsAdmin();
    await mockRequest('PUT', 'http://mock.local/api/v1/admin/config/ai.kill_switch', { value: true, reason: 'test' });
    try {
      renderWithProviders(<Routes><Route path="/copilot" element={<Copilot />} /></Routes>, { route: '/copilot' });
      expect(await screen.findByText(/Connect an LLM key to enable the copilot/, {}, { timeout: T })).toBeInTheDocument();
      expect(screen.getByLabelText('Describe the change in plain English')).toBeDisabled();
    } finally {
      await mockRequest('PUT', 'http://mock.local/api/v1/admin/config/ai.kill_switch', { value: false, reason: 'test' });
    }
  }, T * 2);

  it('blocks users without an editing permission', () => {
    setSession('t', { type: 'USER', id: 'cs', tenantId: 'demo', roles: ['cs_rep'], permissions: ['order.read', 'decision.read'], trustTier: 3 });
    renderWithProviders(<Routes><Route path="/copilot" element={<Copilot />} /></Routes>, { route: '/copilot' });
    expect(screen.getByText('Copilot needs an editing role')).toBeInTheDocument();
  });
});

describe('Bootstrap wizard', () => {
  it('walks three steps and renders the blueprint change set', async () => {
    loginAsAdmin();
    renderWithProviders(<Routes><Route path="/copilot/bootstrap" element={<Bootstrap />} /></Routes>, { route: '/copilot/bootstrap' });
    expect(screen.getByText('Describe your business and get a reviewable configuration bundle', { exact: false })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Use the example' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next: nodes' }));
    fireEvent.click(screen.getByRole('button', { name: 'Use example nodes' }));
    expect(screen.getByDisplayValue('DC-NJ')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next: review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Generate blueprint' }));
    const list = await screen.findByRole('list', { name: 'Proposed changes' }, { timeout: T });
    expect(within(list).getAllByRole('listitem').length).toBeGreaterThanOrEqual(8);
    expect(screen.getByRole('button', { name: 'Stage all' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Stage all' }));
    await screen.findByText(/Policy Studio drafts/, {}, { timeout: T });
    expect(screen.getAllByText(/Studio draft fulfilment_intent/).length).toBeGreaterThan(0);
  }, T * 2);
});

describe('Ops copilot', () => {
  it('asks a question and renders answer, SQL and linked order rows', async () => {
    loginAsAdmin();
    renderWithProviders(<OpsCopilot />);
    await screen.findByText(/Open orders whose SLA breach risk/, {}, { timeout: T });
    fireEvent.change(screen.getByLabelText('Ask the OMS'), { target: { value: 'Which orders are at risk?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await screen.findByText(/at breach risk/, {}, { timeout: T });
    expect(screen.getByText(/Query used:/)).toHaveTextContent('orders_at_risk');
    expect(screen.getByLabelText('SQL')).toHaveTextContent('FROM orders o');
    const links = screen.getAllByRole('link', { name: /^ORD-/ });
    expect(links.length).toBeGreaterThan(0);
    expect(links[0]).toHaveAttribute('href', expect.stringMatching(/^\/orders\/ORD-/));
  }, T * 2);
});

describe('CS reply draft', () => {
  it('classifies intent, drafts an editable reply and exposes Why', async () => {
    loginAsAdmin();
    const orders = await mockRequest<{ items: { id: string }[] }>('GET', 'http://mock.local/api/v1/orders?limit=1');
    const onWhy = vi.fn();
    renderWithProviders(<CsDraftTab orderId={orders.items[0].id} onWhy={onWhy} />);
    fireEvent.click(screen.getByText('Hi, where is my order? It was supposed to arrive yesterday.'));
    fireEvent.click(screen.getByRole('button', { name: 'Draft reply' }));
    expect(await screen.findByLabelText('intent wismo', {}, { timeout: T })).toBeInTheDocument();
    const ta = screen.getByLabelText(/Draft reply/) as HTMLTextAreaElement;
    expect(ta.value).toMatch(new RegExp(orders.items[0].id));
    fireEvent.change(ta, { target: { value: 'edited' } });
    expect(screen.getByRole('button', { name: 'Reset to generated' })).toBeEnabled();
    expect(screen.getByText(/order\.status=/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Why this intent' }));
    expect(onWhy).toHaveBeenCalledWith(expect.stringMatching(/^dr_/));
  }, T * 2);
});

describe('Why drawer explain', () => {
  it('generates a plain-English explanation with the generated badge and raw-trace link', async () => {
    loginAsAdmin();
    const recs = await mockRequest<{ items: DecisionRecord[] }>('GET', 'http://mock.local/api/v1/decision-records?limit=1');
    renderWithProviders(<WhyDrawer recordId={recs.items[0].id} onClose={() => {}} />);
    const btn = await screen.findByRole('button', { name: 'Explain in plain English' }, { timeout: T });
    fireEvent.click(btn);
    const region = await screen.findByRole('region', { name: 'Plain-English explanation' }, { timeout: T });
    expect(within(region).getByText('generated')).toBeInTheDocument();
    expect(within(region).getByText(/source of truth/)).toBeInTheDocument();
    expect(within(region).getByRole('link', { name: /View raw trace/ })).toHaveAttribute('href', '#why-raw-trace');
    await waitFor(() => expect(screen.getByRole('switch')).toBeInTheDocument());
  }, T * 2);
});

describe('Margin Guardian digest', () => {
  it('shows the latest digest with findings, runs a new one and opens a finding in Copilot with the prompt pre-filled', async () => {
    loginAsAdmin();
    renderWithProviders(<Routes><Route path="/analytics" element={<MarginGuardian />} /><Route path="/copilot" element={<Copilot />} /></Routes>, { route: '/analytics' });
    const list = await screen.findByRole('list', { name: 'Digest findings' }, { timeout: T });
    expect(within(list).getAllByRole('listitem').length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText(/Split shipments cost more/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^Digest / })).toHaveLength(2), { timeout: T });
    fireEvent.click(screen.getByRole('button', { name: 'Run digest' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^Digest / })).toHaveLength(3), { timeout: T });
    fireEvent.click(within(list).getAllByRole('button', { name: 'Open in Copilot' })[0]);
    const composer = await screen.findByLabelText('Describe the change in plain English', {}, { timeout: T }) as HTMLTextAreaElement;
    expect(composer.value).toMatch(/split penalty/i);
  }, T * 2);

  it('renders plain-string validation warnings as warning rows', async () => {
    loginAsAdmin();
    const cs = await mockRequest<ChangeSet>('POST', 'http://mock.local/api/v1/copilot/changes', { text: 'Raise the margin floor for marketplace orders to 15%', scope: 'policies' });
    expect(cs.changes[0].validation.warnings?.length).toBe(1);
    renderWithProviders(<Routes><Route path="/copilot" element={<Copilot />} /></Routes>, { route: `/copilot?cs=${cs.id}` });
    const findings = await screen.findAllByLabelText('Validation findings', {}, { timeout: T });
    expect(within(findings[0]).getByText('warning')).toBeInTheDocument();
    expect(within(findings[0]).getByText(/margin approval task/)).toBeInTheDocument();
  }, T * 2);
});

import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { clearSession, hasAnyPermission, hasPermission, useSession } from '../auth/session';
import { useTheme } from '../lib/hooks';
import { API_BASE, MOCK } from '../api/client';
import { ordersApi } from '../api/orders';
import { api } from '../api/client';
import type { Page, HumanTask, ExceptionRecord } from '../api/types';
import { Badge } from '../components/Badge';
import { Button } from '../components/Button';
import { Toasts } from '../components/Toasts';
import { IconAgent, IconAlert, IconChart, IconDiff, IconLogout, IconMoon, IconOrders, IconReturn, IconSearch, IconSettings, IconSim, IconSparkle, IconStates, IconStudio, IconSun, IconTasks, IconTower } from '../components/Icons';
import { useDebounced } from '../lib/hooks';
import { IconPlay } from './launchIcons';
import { Tour } from './Tour';
import { FeedbackButton } from './Feedback';
import { NoKeyBanner } from './NoKeyBanner';
import { TenantBadge } from './TenantBadge';

const GROUPS = [
  { title: 'Operate', items: [
    { to: '/control-tower', label: 'Control Tower', icon: IconTower, perm: 'order.read', aiPulse: true },
    { to: '/playground', label: 'Playground', icon: IconSparkle, perm: 'order.read' },
    { to: '/orders', label: 'Orders', icon: IconOrders, perm: 'order.read' },
    { to: '/tasks', label: 'Tasks', icon: IconTasks, perm: 'task.read', count: 'tasks' },
    { to: '/exceptions', label: 'Exceptions', icon: IconAlert, perm: 'exception.read', count: 'exceptions' },
    { to: '/returns', label: 'Returns', icon: IconReturn, perm: 'order.read' },
  ] },
  { title: 'Govern', items: [
    { to: '/studio', label: 'Policy Studio', icon: IconStudio, perm: 'decision.read' },
    { to: '/state-models', label: 'State Models', icon: IconStates, perm: 'order.read' },
    { to: '/agents', label: 'Agents', icon: IconAgent, perm: 'order.read' },
    { to: '/simulation', label: 'Simulation', icon: IconSim, perm: 'simulation.run' },
    { to: '/replay', label: 'Margin Replay', icon: IconDiff, perm: 'simulation.run' },
    { to: '/copilot', label: 'Copilot', icon: IconSparkle, perms: ['decision.edit', 'statemodel.edit', 'config.admin'] },
  ] },
  { title: 'Insight', items: [
    { to: '/analytics', label: 'Analytics', icon: IconChart, perm: 'analytics.read' },
    { to: '/learning', label: 'Learning', icon: IconSparkle, perm: 'learning.read' },
  ] },
  { title: 'Admin', items: [{ to: '/settings', label: 'Settings', icon: IconSettings, perm: 'config.admin' }] },
  { title: 'Explore', items: [{ to: '/showcase', label: 'Showcase', icon: IconPlay, perm: '' }] },   // visible to everyone
];

export function AppShell() {
  const { principal } = useSession();
  const navigate = useNavigate();
  const [theme, setTheme] = useTheme();
  const counts = useQuery({ queryKey: ['nav-counts'], queryFn: async () => {
    const [t, e, a] = await Promise.all([
      api.get<Page<HumanTask>>('/api/v1/tasks', { query: { status: 'OPEN', limit: 1, mine: true }, silent: true }).catch(() => null),
      api.get<Page<ExceptionRecord>>('/api/v1/exceptions', { query: { status: 'active', limit: 1 }, silent: true }).catch(() => null),
      api.get<{ actionsToday: number; humanEscalations: number }>('/api/v1/autonomy/stats', { silent: true }).catch(() => null),
    ]);
    return { tasks: t?.total ?? 0, exceptions: e?.total ?? 0, autonomyActive: !!a && (a.actionsToday > 0 || a.humanEscalations > 0) };
  }, refetchInterval: 15000, staleTime: 10000 });
  const isDark = theme === 'dark' || (theme === 'system' && typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
  const logout = () => { clearSession(); navigate('/login'); };
  return (
    <div className="shell">
      <nav className="nav" aria-label="Primary">
        <div className="nav-brand"><span className="logo" aria-hidden>IO</span><span className="txt">Intent OMS</span>{MOCK && <Badge tone="warn" className="env">mock</Badge>}</div>
        <div className="nav-scroll">
          {GROUPS.map((g) => { const items = g.items.filter((i) => ('perms' in i && i.perms ? hasAnyPermission(principal, i.perms) : !('perm' in i) || !i.perm || hasPermission(principal, i.perm))); if (!items.length) return null; return (
            <div className="nav-group" key={g.title}>
              <h4>{g.title}</h4>
              {items.map((i) => <NavLink key={i.to} to={i.to} className={({ isActive }) => ['nav-link', isActive && 'active'].filter(Boolean).join(' ')}><i.icon /><span>{i.label}</span>{'count' in i && i.count && counts.data && counts.data[i.count as 'tasks' | 'exceptions'] > 0 && <span className="cnt" aria-label={`${counts.data[i.count as 'tasks' | 'exceptions']} open${i.count === 'tasks' ? ' in your queues' : ''}`} title={i.count === 'tasks' ? 'Open tasks in the queues you work' : undefined}>{counts.data[i.count as 'tasks' | 'exceptions']}</span>}{'aiPulse' in i && i.aiPulse && counts.data?.autonomyActive && <span className="live ai" aria-label="Autonomous actions active today" title="Autonomous actions active today" style={{ marginLeft: 4 }}><span className="pulse" aria-hidden /></span>}</NavLink>)}
            </div>); })}
        </div>
        <div className="nav-foot"><span>v0.1.0</span><span>{MOCK ? 'mock API' : `api ${API_BASE ? new URL(API_BASE, location.href).host : location.host}`}</span></div>
      </nav>
      <header className="topbar">
        <GlobalSearch />
        <div className="who">
          <TenantBadge onSwitch={logout} />
          <span className="avatar" aria-hidden>{(principal?.id ?? '?').slice(0, 2)}</span>
          <span className="small strong">{principal?.id}</span>
          <span className="pill-row">{principal?.roles.map((r) => <Badge key={r} tone="outline">{r.replace(/_/g, ' ')}</Badge>)}</span>
          <FeedbackButton />
          <Button variant="ghost" iconOnly icon={isDark ? <IconSun /> : <IconMoon />} onClick={() => setTheme(isDark ? 'light' : 'dark')} title={`Switch to ${isDark ? 'light' : 'dark'} theme`}>Toggle theme</Button>
          <Button variant="ghost" iconOnly icon={<IconLogout />} onClick={logout} title="Log out">Log out</Button>
        </div>
      </header>
      <main className="main" id="main"><NoKeyBanner /><Outlet /></main>
      <Tour />
      <Toasts />
    </div>
  );
}

function GlobalSearch() {
  const [q, setQ] = useState(''); const [open, setOpen] = useState(false); const [idx, setIdx] = useState(0);
  const dq = useDebounced(q.trim(), 250); const navigate = useNavigate(); const ref = useRef<HTMLInputElement>(null);
  const res = useQuery({ queryKey: ['gsearch', dq], queryFn: () => ordersApi.search({ q: dq, limit: 6 }), enabled: dq.length >= 2 });
  useEffect(() => { const h = (e: KeyboardEvent) => { if (e.key === '/' && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) { e.preventDefault(); ref.current?.focus(); } }; window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h); }, []);
  const items = res.data?.items ?? [];
  const go = (id: string) => { navigate(`/orders/${encodeURIComponent(id)}`); setQ(''); setOpen(false); };
  return (
    <div className="gsearch" role="combobox" aria-expanded={open && dq.length >= 2} aria-haspopup="listbox" aria-owns="gsearch-list" aria-controls="gsearch-list">
      <IconSearch className="ico" size={15} />
      <input ref={ref} className="input" placeholder="Search order id, channel order id, customer, SKU…" value={q} aria-label="Global search" aria-autocomplete="list" aria-controls="gsearch-list" aria-activedescendant={open && items[idx] ? `gs-${items[idx].id}` : undefined}
        onChange={(e) => { setQ(e.target.value); setOpen(true); setIdx(0); }} onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={(e) => { if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(items.length - 1, i + 1)); } else if (e.key === 'ArrowUp') { e.preventDefault(); setIdx((i) => Math.max(0, i - 1)); } else if (e.key === 'Enter') { if (items[idx]) go(items[idx].id); else if (dq) { navigate(`/orders?q=${encodeURIComponent(dq)}`); setOpen(false); } } else if (e.key === 'Escape') setOpen(false); }} />
      <span className="kbd" aria-hidden>/</span>
      {open && dq.length >= 2 && (
        <div className="gsearch-pop" id="gsearch-list" role="listbox">
          {res.isLoading && <div className="hint">Searching…</div>}
          {!res.isLoading && items.length === 0 && <div className="hint">No orders match “{dq}”. Press Enter to open the orders list with this filter.</div>}
          {items.map((o, i) => <button key={o.id} id={`gs-${o.id}`} role="option" aria-selected={i === idx} className="opt" onMouseDown={() => go(o.id)} onMouseEnter={() => setIdx(i)}><span className="mono">{o.id}</span><span className="muted small">{o.channel} · {o.channelOrderId}</span><span className="grow" /><Badge tone="outline">{o.status}</Badge></button>)}
          {items.length > 0 && <div className="hint">↑↓ to move · Enter to open · Esc to close</div>}
        </div>
      )}
    </div>
  );
}

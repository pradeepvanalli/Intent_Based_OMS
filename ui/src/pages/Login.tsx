import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi, type PublicTenant } from '../api/auth';
import { ApiError, MOCK } from '../api/client';
import { setSession } from '../auth/session';
import { Button } from '../components/Button';
import { Input, Select } from '../components/Form';
import { Badge } from '../components/Badge';

const DEMO = [['admin', 'admin'], ['ops', 'ops analyst'], ['cs', 'cs rep'], ['risk', 'risk analyst'], ['finance', 'finance controller'], ['strategy', 'strategy lead'], ['platform', 'platform engineer']];

export default function LoginPage() {
  const [tenantId, setTenant] = useState('demo'); const [userId, setUser] = useState(''); const [password, setPassword] = useState('');
  const [tenants, setTenants] = useState<PublicTenant[]>([]);
  const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  const navigate = useNavigate(); const [sp] = useSearchParams();
  // Seeded tenants for the picker; any failure (production, no seed, network) leaves the free-text input in place.
  useEffect(() => {
    let cancelled = false;
    authApi.publicTenants()
      .then((list) => { if (cancelled || !Array.isArray(list) || list.length === 0) return; setTenants(list); setTenant((cur) => (list.some((t) => t.tenantId === cur) ? cur : list[0].tenantId)); })
      .catch(() => { /* keep the text input */ });
    return () => { cancelled = true; };
  }, []);
  const selected = tenants.find((t) => t.tenantId === tenantId);
  const submit = async (e?: FormEvent, u = userId, p = password) => {
    e?.preventDefault(); setBusy(true); setError(null);
    try { const res = await authApi.login({ tenantId, userId: u, password: p }); setSession(res.token, res.principal); navigate(sp.get('next') ?? '/control-tower', { replace: true }); }
    catch (err) { setError(err instanceof ApiError ? (err.problem.detail ?? 'Invalid credentials') : 'Could not reach the API'); }
    finally { setBusy(false); }
  };
  return (
    <div className="login">
      <aside className="brand-side">
        <div className="brand-mark"><span className="logo">IO</span><span>Intent OMS</span></div>
        <div className="col gap4">
          <h1>Every order decision,<br /><span className="accent-word">explainable.</span></h1>
          <p>Rules decide the clear cases. Jev scores the judgement calls. An LLM steps in only when it must. A person sees only what nobody else is sure about — and every outcome carries a “Why”.</p>
          <div className="pills">{['Agentic control tower', 'Policy studio', 'Margin floor', 'Agent mandates', 'Calibration', 'Kill switches'].map((p) => <span key={p}>{p}</span>)}</div>
          <div className="proof">
            <div><b>&lt;1s</b><span>typed Jev decisions</span></div>
            <div><b>100%</b><span>decisions carry a trace</span></div>
            <div><b>0</b><span>silent autonomous spend</span></div>
          </div>
        </div>
        <div className="small" style={{ opacity: .7 }}>Tenant data stays in your region · WCAG 2.2 AA</div>
      </aside>
      <section className="form-side">
        <form className="form-card" onSubmit={submit} aria-describedby={error ? 'login-error' : undefined}>
          <div><h2>Sign in</h2><div className="muted mt2">Use your tenant credentials{MOCK && <> — <Badge tone="warn">mock API</Badge></>}</div></div>
          {tenants.length > 0
            ? <Select label="Tenant" value={tenantId} onChange={(e) => setTenant(e.target.value)} autoComplete="organization" required hint={selected?.description ?? undefined}>
                {tenants.map((t) => <option key={t.tenantId} value={t.tenantId}>{t.name}{t.vertical ? ` · ${t.vertical}` : ''} ({t.tenantId})</option>)}
              </Select>
            : <Input label="Tenant" value={tenantId} onChange={(e) => setTenant(e.target.value)} autoComplete="organization" required mono />}
          <Input label="User" value={userId} onChange={(e) => setUser(e.target.value)} autoComplete="username" required autoFocus />
          <Input label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          {error && <div className="error-box" id="login-error" role="alert"><span className="title">Sign-in failed</span><span className="small">{error}</span></div>}
          <Button type="submit" variant="primary" size="lg" loading={busy}>Sign in</Button>
          <div className="divider" />
          <div className="small muted">Demo users on tenant <code>{tenantId}</code> (password equals user name):</div>
          <div className="demo-users">{DEMO.map(([u, role]) => <button type="button" key={u} onClick={() => { setUser(u); setPassword(u); void submit(undefined, u, u); }}><b>{u}</b><span className="muted">{role}</span></button>)}</div>
        </form>
      </section>
    </div>
  );
}

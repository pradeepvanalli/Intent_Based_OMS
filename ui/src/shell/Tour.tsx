import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useSession } from '../auth/session';
import { Button } from '../components/Button';
import { advance, back, cardPosition, hasSeenTour, markTourSeen, TOUR_RESTART_EVENT, TOUR_STEPS, type Rect } from './tourSteps';

const CARD = { width: 340, height: 190 };
const anchorFor = (target: string): HTMLElement | null => (typeof document === 'undefined' ? null : document.querySelector<HTMLElement>(`a.nav-link[href="${target}"]`));

/** Six-step first-run tour anchored to the primary nav; opens once per tenant (localStorage) and on `restartTour()`. */
export function Tour() {
  const { principal } = useSession();
  const tenant = principal?.tenantId;
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [pos, setPos] = useState<{ top: number; left: number }>({ top: 72, left: 260 });
  const [ring, setRing] = useState<Rect | null>(null);
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!tenant || hasSeenTour(tenant)) return;
    const t = setTimeout(() => { setStep(0); setOpen(true); }, 700);
    return () => clearTimeout(t);
  }, [tenant]);
  useEffect(() => {
    const h = () => { setStep(0); setOpen(true); };
    window.addEventListener(TOUR_RESTART_EVENT, h);
    return () => window.removeEventListener(TOUR_RESTART_EVENT, h);
  }, []);

  const place = useCallback(() => {
    const el = anchorFor(TOUR_STEPS[step].target);
    const r = el?.getBoundingClientRect() ?? null;
    const rect: Rect | null = r ? { top: r.top, left: r.left, right: r.right, bottom: r.bottom, width: r.width, height: r.height } : null;
    setRing(rect);
    setPos(cardPosition(rect, { width: window.innerWidth, height: window.innerHeight }, CARD));
  }, [step]);
  useLayoutEffect(() => { if (!open) return; place(); window.addEventListener('resize', place); return () => window.removeEventListener('resize', place); }, [open, place]);
  useEffect(() => { if (open) cardRef.current?.focus(); }, [open, step]);

  const finish = useCallback(() => { markTourSeen(tenant); setOpen(false); }, [tenant]);
  const next = () => { const n = advance(step); if (n === null) finish(); else setStep(n); };
  useEffect(() => { if (!open) return; const h = (e: KeyboardEvent) => { if (e.key === 'Escape') finish(); }; window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h); }, [open, finish]);

  if (!open || !tenant) return null;
  const s = TOUR_STEPS[step];
  const last = advance(step) === null;
  return (
    <>
      {ring && <div aria-hidden style={{ position: 'fixed', top: ring.top - 3, left: ring.left - 3, width: ring.width + 6, height: ring.height + 6, borderRadius: 8, boxShadow: '0 0 0 2px var(--accent), 0 0 0 6px var(--accent-soft)', pointerEvents: 'none', zIndex: 79 }} />}
      <div ref={cardRef} tabIndex={-1} role="dialog" aria-label={`Tour step ${step + 1} of ${TOUR_STEPS.length}: ${s.title}`} data-testid="tour-card"
        style={{ position: 'fixed', top: pos.top, left: pos.left, width: CARD.width, zIndex: 80, background: 'var(--surface)', border: '1px solid var(--border-strong)', borderRadius: 'var(--r3)', boxShadow: 'var(--shadow-2)', padding: 'var(--s4)', display: 'flex', flexDirection: 'column', gap: 'var(--s2)', outline: 'none' }}>
        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <span className="section-title" style={{ margin: 0 }}>Tour · {step + 1} of {TOUR_STEPS.length}</span>
          <button className="btn btn-sm btn-ghost" onClick={finish} aria-label="Skip tour">Skip</button>
        </div>
        <div style={{ fontWeight: 650 }}>{s.title}</div>
        <div className="small" style={{ lineHeight: 1.55 }}>{s.body}</div>
        {!ring && <div className="small muted">This section is not in your navigation yet{s.target === '/playground' ? ' (coming with the Playground)' : ''}.</div>}
        <div className="row gap2" style={{ alignItems: 'center', marginTop: 4 }}>
          <Link to={s.target} className="small" onClick={() => { if (last) finish(); }}>Open {s.title}</Link>
          <span className="grow" />
          {step > 0 && <Button size="sm" variant="ghost" onClick={() => setStep(back(step))}>Back</Button>}
          <Button size="sm" variant="primary" onClick={next}>{last ? 'Finish' : 'Next'}</Button>
        </div>
      </div>
    </>
  );
}

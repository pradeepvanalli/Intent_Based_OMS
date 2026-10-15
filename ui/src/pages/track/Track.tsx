import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { trackingApi, type TrackingView } from '../../api/playground';
import { Loading } from '../../components/States';
import { IconCheck, IconExternal, IconInfo, IconSparkle } from '../../components/Icons';
import { fmtDate, fmtDateTime } from '../../lib/format';
import './track.css';

/** Customer-facing tracking page at /track/:token. No session, no shell: the signed token is the whole credential. */
export default function Track() {
  const { token = '' } = useParams();
  const q = useQuery({ queryKey: ['track', token], queryFn: () => trackingApi.view(token), enabled: !!token, retry: 1, refetchInterval: 60_000 });
  return (
    <main className="trk">
      <div className="trk-wrap">
        <div className="trk-brand"><span className="mark" aria-hidden="true">IO</span>Intent OMS</div>
        <div className="trk-card">
          {q.isLoading && <div className="trk-body"><Loading label="Looking up your order…" /></div>}
          {q.isError && (
            <div className="trk-error" role="alert">
              <span className="trk-error-icon" aria-hidden="true"><IconInfo size={22} /></span>
              <div className="eyebrow">Order tracking</div>
              <h1>We couldn't find that order</h1>
              <p className="muted">This tracking link is invalid or has expired. Please use the latest link from your order confirmation, or contact us with your order number.</p>
            </div>
          )}
          {q.data && <TrackBody v={q.data} onRefresh={() => q.refetch()} refreshing={q.isFetching} />}
        </div>
      </div>
    </main>
  );
}

const STAGES = ['placed', 'confirmed', 'shipped', 'delivered'] as const;

function stepLabels(v: Pick<TrackingView, 'pickup'>): Record<(typeof STAGES)[number], string> {
  return v.pickup
    ? { placed: 'Order placed', confirmed: 'Being prepared', shipped: 'Ready for pickup', delivered: 'Picked up' }
    : { placed: 'Order placed', confirmed: 'Confirmed', shipped: 'Shipped', delivered: 'Delivered' };
}

export function TrackBody({ v, onRefresh, refreshing }: { v: TrackingView; onRefresh?: () => void; refreshing?: boolean }) {
  const cancelled = v.stage === 'cancelled';
  const idx = cancelled ? -1 : STAGES.indexOf(v.stage as (typeof STAGES)[number]);
  const labels = stepLabels(v);
  const paragraphs = (v.narrative ?? '').split(/\n{2,}/).filter(Boolean);
  return (
    <>
      <header className="trk-head">
        <span className="eyebrow">Order tracking</span>
        <h1>Order <span className="num">{v.orderNumber}</span></h1>
        <div className={['status', v.stage].join(' ')}>{v.statusLabel}</div>
        <div className="muted small">Placed {fmtDateTime(v.placedAt)}</div>
      </header>
      <div className="trk-body">
        {cancelled
          ? <div className="trk-cancel" role="status">This order was cancelled.</div>
          : <ol className={['trk-steps', cancelled && 'cancelled'].filter(Boolean).join(' ')} aria-label="Order progress">
              {STAGES.map((s, i) => <li key={s} className={['trk-step', i < idx && 'done', i === idx && 'current'].filter(Boolean).join(' ')} aria-current={i === idx ? 'step' : undefined}><span className="dot" aria-hidden>{i < idx ? <IconCheck size={14} /> : i + 1}</span><span className="lbl">{labels[s]}</span></li>)}
            </ol>}
        <section className="trk-narrative" aria-label="Status in plain words">
          {paragraphs.map((p, i) => <p key={i}>{p}</p>)}
          {v.narrativeGenerated && <span className="gen"><IconSparkle size={11} /> Part of this update was written by an assistant, grounded only in your order's facts.</span>}
        </section>
        {v.nextStep && <div className="trk-next"><b>What happens next.</b> {v.nextStep}</div>}
        {(v.promise || v.pickupLocation) && (
          <section className="trk-section"><h2>{v.pickup ? 'Pickup' : 'Delivery'}</h2>
            <div className="trk-promise">
              {v.promise && <div className="tile"><span className="k">{v.pickup ? 'Ready by' : 'Expected'}</span><span className="v">{v.promise.earliest === v.promise.latest ? fmtDate(v.promise.latest) : `${fmtDate(v.promise.earliest)} – ${fmtDate(v.promise.latest)}`}</span>{v.promise.shipMethod && !v.pickup && <span className="muted small">{v.promise.shipMethod.replace(/_/g, ' ')}</span>}</div>}
              {v.pickupLocation && <div className="tile"><span className="k">Pickup store</span><span className="v">{v.pickupLocation.storeName}</span>{(v.pickupLocation.city || v.pickupLocation.state) && <span className="muted small">{[v.pickupLocation.city, v.pickupLocation.state].filter(Boolean).join(', ')}</span>}</div>}
            </div>
          </section>
        )}
        {v.shipments.length > 0 && (
          <section className="trk-section"><h2>{v.shipments.length === 1 ? 'Shipment' : `${v.shipments.length} shipments`}</h2>
            <div className="col gap3">
              {v.shipments.map((s, i) => (
                <div key={i} className="trk-ship">
                  <div className="hd"><b>{s.carrier ?? 'Carrier'}{s.service ? <span className="muted"> · {s.service}</span> : null}</b><span className={['st', s.status === 'Delivered' && 'ok'].filter(Boolean).join(' ')}>{s.status}</span></div>
                  {s.tracking && <div className="row wrap gap3"><span className="trk-no">{s.tracking}</span>{s.trackingUrl && <a href={s.trackingUrl} target="_blank" rel="noreferrer" className="small">Track with {s.carrier} <IconExternal size={12} /></a>}</div>}
                  <div className="items">{s.items.join(', ')}{s.deliveredAt ? ` · delivered ${fmtDateTime(s.deliveredAt)}` : s.eta ? ` · expected ${fmtDate(s.eta)}` : s.shippedAt ? ` · shipped ${fmtDateTime(s.shippedAt)}` : ''}</div>
                </div>
              ))}
            </div>
          </section>
        )}
        <section className="trk-section"><h2>Items</h2>
          <ul className="trk-items">{v.items.map((it, i) => <li key={i}><span>{it.name}</span><span className="qty">× {it.qty}</span><span className={['st', ['Delivered', 'Picked up', 'Shipped'].includes(it.status) && 'ok'].filter(Boolean).join(' ')}>{it.status}</span></li>)}</ul>
        </section>
      </div>
      <footer className="trk-foot">
        <span>Updated {fmtDateTime(v.generatedAt)}{onRefresh && <> · <button type="button" onClick={onRefresh} disabled={refreshing}>{refreshing ? 'refreshing…' : 'refresh'}</button></>}</span>
        <span>This link works until {fmtDate(v.expiresAt)}</span>
      </footer>
    </>
  );
}

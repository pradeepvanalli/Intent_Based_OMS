// NF-08: episode assembly keeps up with 10x the tenant's peak hourly order rate.
//
// Drives real orders through POST /api/v1/orders at a constant arrival rate of PEAK_ORDERS_PER_HOUR x MULTIPLIER
// (default 360/h x 10 = 1 order per second) for DURATION. Every order runs the normal workflow, whose decisions emit
// decision.evaluated, which EpisodeAssembler turns into episodes. Run scripts/perf/episode-assembly.sh to also
// measure, from the database, whether every LIVE decision of the run got its episode and how long assembly lagged.
//
//   BASE=http://localhost:8105 PEAK_ORDERS_PER_HOUR=360 MULTIPLIER=10 DURATION=5m k6 run scripts/perf/episode-assembly.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TENANT = __ENV.TENANT || 'demo';
const PEAK = Number(__ENV.PEAK_ORDERS_PER_HOUR || 360);
const MULTIPLIER = Number(__ENV.MULTIPLIER || 10);
const RATE_PER_MIN = Math.max(1, Math.round((PEAK * MULTIPLIER) / 60));
const RUN = __ENV.RUN_ID || `${Date.now()}`;

const ordersCreated = new Counter('orders_created');
const createLatency = new Trend('order_create_ms', true);

export const options = {
  scenarios: {
    ten_x_peak: {
      executor: 'constant-arrival-rate',
      rate: RATE_PER_MIN,
      timeUnit: '1m',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    order_create_ms: ['p(95)<2000'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ userId: __ENV.OMS_USER || 'admin', password: __ENV.OMS_PASSWORD || 'admin', tenantId: TENANT }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'logged in': (r) => r.status === 200 });
  return { token: res.json('token'), startedAt: new Date().toISOString() };
}

const SKUS = [['HOOD-GRY-M', 68.0], ['CAP-NVY', 32.0], ['TEE-BLK-M', 24.0], ['SOCK-3PK', 14.0], ['BAG-TOTE', 29.0]];
const CITIES = [['Seattle', 'WA', '98101'], ['Austin', 'TX', '78701'], ['Newark', 'NJ', '07102'], ['Chicago', 'IL', '60601'], ['Denver', 'CO', '80202']];

export default function (data) {
  const n = `${RUN}-${__VU}-${__ITER}`;
  const [sku1, p1] = SKUS[__ITER % SKUS.length];
  const [sku2, p2] = SKUS[(__ITER + 2) % SKUS.length];
  const [city, state, zip] = CITIES[__VU % CITIES.length];
  const total = ((p1 + p2) * 1.2).toFixed(2);   // covers tax and shipping; the excess is not charged
  const body = {
    channel: 'web', channelOrderId: `NF08-${n}`, orderType: 'standard_ship', customerRef: `cust-nf08-${__VU % 50}`,
    customer: { ref: `cust-nf08-${__VU % 50}`, tier: 'silver', history: { split_acceptance_rate: 0.4 } },
    lines: [
      { lineId: '1', sku: sku1, qty: 1, unitPrice: { amount: p1, currency: 'USD' } },
      { lineId: '2', sku: sku2, qty: 1, unitPrice: { amount: p2, currency: 'USD' } },
    ],
    addresses: [{ type: 'ship_to', name: 'Load Test', line1: '1 Test St', city, state, zip, country: 'US' }],
    tenders: [{ type: 'card', token: 'tok_visa_ok', amount: { amount: Number(total), currency: 'USD' } }],
    notes: [{ source: 'customer', text: __ITER % 4 === 0 ? 'Please ship everything together' : 'Fastest delivery please' }],
  };
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}`, 'X-Tenant-Id': TENANT },
    tags: { name: 'create_order' },
  });
  createLatency.add(res.timings.duration);
  if (check(res, { 'order accepted': (r) => r.status === 200 || r.status === 201 })) ordersCreated.add(1);
}

export function teardown(data) {
  console.log(`run=${RUN} started=${data.startedAt} ended=${new Date().toISOString()} target=${RATE_PER_MIN}/min (${PEAK}/h x ${MULTIPLIER})`);
}

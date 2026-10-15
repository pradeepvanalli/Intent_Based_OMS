// k6 load test: promise reads (NFR-02 p95 < 300 ms) and order capture (NFR-02 ack < 200 ms).
// Usage: k6 run -e BASE=http://localhost:8080 -e TOKEN=<jwt> load/promise.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const TENANT = __ENV.TENANT || 'demo';
const promiseLatency = new Trend('promise_latency', true);
const captureLatency = new Trend('capture_latency', true);

export const options = {
  scenarios: {
    promise: { executor: 'constant-arrival-rate', rate: Number(__ENV.PROMISE_RPS || 200), timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 50, maxVUs: 400, exec: 'promise' },
    capture: { executor: 'constant-arrival-rate', rate: Number(__ENV.CAPTURE_RPS || 20), timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 20, maxVUs: 200, exec: 'capture' },
  },
  thresholds: {
    promise_latency: ['p(95)<300'],
    capture_latency: ['p(95)<200'],
    http_req_failed: ['rate<0.01'],
  },
};

const skus = ['TEE-BLK-M', 'HOOD-GRY-M', 'JEAN-IND-32', 'SNK-RUN-10', 'BAG-TOTE', 'MUG-CER', 'YOGA-MAT', 'BOTL-INS'];
const zips = ['10001', '60611', '90036', '75201', '30303', '98101', '33101', '02108'];
const headers = { 'Content-Type': 'application/json', 'X-Tenant-Id': TENANT, ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}) };

export function promise() {
  const sku = skus[Math.floor(Math.random() * skus.length)];
  const zip = zips[Math.floor(Math.random() * zips.length)];
  const res = http.post(`${BASE}/api/v1/promise`, JSON.stringify({ lines: [{ sku, qty: 1 }], destinationZip: zip, destinationState: 'NY', channel: 'web', shipMethod: 'ground' }), { headers });
  promiseLatency.add(res.timings.duration);
  check(res, { 'promise 200': (r) => r.status === 200 });
}

export function capture() {
  const id = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const body = {
    channel: 'web', channelOrderId: id, orderType: 'standard_ship', customerRef: `cust-${__VU}`,
    customer: { email: `load${__VU}@example.com`, name: 'Load Test', tier: 'silver' },
    lines: [{ lineId: '1', sku: skus[__ITER % skus.length], qty: 1 + (__ITER % 3), unitPrice: { amount: 28.0, currency: 'USD' } }],
    addresses: [{ type: 'ship_to', line1: '1 Main St', city: 'New York', state: 'NY', zip: '10001', country: 'US' }],
    tenders: [{ type: 'card', token: 'tok_visa_ok', amount: { amount: 28.0, currency: 'USD' } }],
    intentChoices: ['ship_as_available'],
  };
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify(body), { headers: { ...headers, 'Idempotency-Key': id } });
  captureLatency.add(res.timings.duration);
  check(res, { 'capture 201/200': (r) => r.status === 201 || r.status === 200 });
  sleep(0.01);
}

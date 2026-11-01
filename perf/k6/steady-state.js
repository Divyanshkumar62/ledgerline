// k6 steady-state scenario for POST /api/v1/transfers (Week 7).
//
// Run (k6 in Docker, app on the host):
//   docker run --rm -v "$PWD/perf/k6:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_KEY=<bootstrap admin key> \
//     grafana/k6:0.54.0 run --summary-export /scripts/results/summary.json /scripts/steady-state.js
//
// Load model: OPEN (constant arrival rate). Requests arrive at a fixed rate whether or not earlier
// ones finished, the way real clients behave. A closed model (N looping VUs) would slow its own
// arrivals when the server slows, hiding latency ("coordinated omission").
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_KEY = __ENV.ADMIN_KEY || 'dev-admin-key-change-me-0123456789';
const ACCOUNTS = 50;
const FUNDING = 10_000_000;           // minor units per account
const STEADY_RATE = Number(__ENV.RATE || 200);
const REPLAY_SHARE = 0.05;            // 5% of requests re-send an earlier key

const replays = new Counter('ledger_replays');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '15s',
      preAllocatedVUs: 20, maxVUs: 100, tags: { phase: 'warmup' },
    },
    steady: {
      executor: 'constant-arrival-rate', rate: STEADY_RATE, timeUnit: '1s', duration: '60s', startTime: '15s',
      preAllocatedVUs: 50, maxVUs: 300, tags: { phase: 'steady' },
    },
  },
  thresholds: {
    // Correctness thresholds hold from the first run. The latency target is set from measured data
    // (tracker rule 4); see docs/benchmarks/k6-steady-state.md for how it was derived.
    'http_req_failed{phase:steady}': ['rate<0.001'],
    'checks{phase:steady}': ['rate>0.999'],
    'dropped_iterations': ['count<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
// Steady-phase latency. Without P99_MS the threshold only observes ('p(99)>=0' always passes): k6 reports a
// tagged sub-metric only when a threshold references it, and the warm-up must not pollute the numbers.
options.thresholds['http_req_duration{phase:steady}'] = [__ENV.P99_MS ? `p(99)<${__ENV.P99_MS}` : 'p(99)>=0'];

function post(path, body, apiKey, headers) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body),
    { headers: Object.assign({ Authorization: `Bearer ${apiKey}` }, JSON_HEADERS, headers) });
}

export function setup() {
  const runId = `k6-${Date.now()}`;
  // The admin issues this run's client key; the client then owns every account it creates.
  const created = post('/api/v1/admin/clients', { clientId: runId, displayName: 'k6 load test', role: 'CLIENT' }, ADMIN_KEY);
  if (created.status !== 201) {
    throw new Error(`client creation failed: ${created.status} ${created.body}`);
  }
  const key = created.json('apiKey');
  const equity = post('/api/v1/accounts', { type: 'EQUITY', currency: 'USD', disallowNegativeBalance: false }, key).json('id');
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const id = post('/api/v1/accounts', { type: 'ASSET', currency: 'USD', disallowNegativeBalance: true }, key).json('id');
    const funded = post('/api/v1/transfers', transferBody(equity, id, FUNDING, 'k6 funding'), key,
      { 'Idempotency-Key': `${runId}-fund-${i}` });
    if (funded.status !== 201) {
      throw new Error(`funding failed: ${funded.status} ${funded.body}`);
    }
    accounts.push(id);
  }
  return { runId, apiKey: key, accounts };
}

function transferBody(from, to, amount, description) {
  return {
    description, currency: 'USD', legs: [
      { accountId: from, direction: 'CREDIT', amountMinor: amount },
      { accountId: to, direction: 'DEBIT', amountMinor: amount },
    ],
  };
}

// Per-VU memory of the last request, so a replay re-sends exactly the same key AND body.
let last = null;

export default function (data) {
  const replay = last !== null && Math.random() < REPLAY_SHARE;
  let key;
  let body;
  if (replay) {
    ({ key, body } = last);
  } else {
    const from = Math.floor(Math.random() * ACCOUNTS);
    const to = (from + 1 + Math.floor(Math.random() * (ACCOUNTS - 1))) % ACCOUNTS;
    key = `${data.runId}-${__VU}-${__ITER}`;
    body = transferBody(data.accounts[from], data.accounts[to], 1 + Math.floor(Math.random() * 100), 'k6 transfer');
  }

  const res = post('/api/v1/transfers', body, data.apiKey, { 'Idempotency-Key': key });
  check(res, {
    'status 201': (r) => r.status === 201,
    'replay marked HIT-IDEMPOTENT': (r) => !replay || r.headers['X-Cache-Lookup'] === 'HIT-IDEMPOTENT',
  });
  if (replay) {
    replays.add(1);
  }
  last = { key, body };
}

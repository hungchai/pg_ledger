// HTTP TPS for Grafana. Postgres panels do not need this; they scrape the databases.
//
// K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),avg \
// k6 run -o experimental-prometheus-rw --tag testid=$(date +%s) k6/postings.js
//
// API must already be listening (default http://127.0.0.1:8080).
// VUS=50 DURATION=60s BASE_URL=http://127.0.0.1:8080

import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const vus = Number(__ENV.VUS || 50);
const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };

export const options = {
  scenarios: {
    postings: {
      executor: 'constant-vus',
      vus: vus,
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    'http_req_failed{name:POST /api/v1/postings}': ['rate==0'],
  },
};

export function setup() {
  const created = http.post(
    `${base}/api/v1/balance-types`,
    JSON.stringify({ code: 'AVAILABLE', name: 'Available' }),
    { headers: headers },
  );
  if (created.status !== 200 && created.status !== 422) {
    throw new Error(`balance type ${created.status} ${created.body}`);
  }
  const ids = [];
  for (let i = 0; i < vus; i++) {
    ids.push(`K6_${i}`);
  }
  create(account('K6_COMPANY', true));
  for (let i = 0; i < ids.length; i++) {
    create(account(ids[i], true));
  }
  return { ids: ids };
}

export default function (data) {
  const from = data.ids[(__VU - 1) % data.ids.length];
  let to = data.ids[__VU % data.ids.length];
  if (to === from) {
    to = 'K6_COMPANY';
  }
  const res = http.post(
    `${base}/api/v1/postings`,
    JSON.stringify({
      fromAccountId: from,
      fromBalanceType: 'AVAILABLE',
      toAccountId: to,
      toBalanceType: 'AVAILABLE',
      currency: 'USD',
      amount: 1,
    }),
    { headers: headers, tags: { name: 'POST /api/v1/postings' } },
  );
  check(res, { posted: (r) => r.status === 200 });
}

function account(id, allowNegative) {
  return {
    accountId: id,
    balanceType: 'AVAILABLE',
    currency: 'USD',
    name: id,
    allowNegativeBalance: allowNegative,
    allowPositiveBalance: true,
  };
}

function create(body) {
  const res = http.post(`${base}/api/v1/accounts`, JSON.stringify(body), { headers: headers });
  if (res.status !== 200 && res.status !== 422) {
    throw new Error(`account ${res.status} ${res.body}`);
  }
}

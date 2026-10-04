/**
 * Cash deposit stress: 100 CASH accounts × ETH/BTC/USDT, 20 VUs.
 *
 * K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:9090/api/v1/write \
 * K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),avg \
 * k6 run -o experimental-prometheus-rw --tag testid=deposit-$(date +%s) k6/deposit.js
 */
import { check } from 'k6';
import {
  accountsForVu,
  amounts,
  currencies,
  duration,
  ensureCashAccounts,
  postJson,
  requestId,
  vus,
  writeSummary,
} from './lib.js';

const reportPath = __ENV.REPORT_PATH || 'reports/latest/01-deposit.txt';

export const options = {
  scenarios: {
    deposits: {
      executor: 'constant-vus',
      vus: vus,
      duration: duration,
    },
  },
  thresholds: {
    'http_req_failed{name:POST /api/v1/deposits}': ['rate==0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

export function setup() {
  ensureCashAccounts();
  return { ok: true };
}

export default function () {
  const ids = accountsForVu(__VU);
  const accountId = ids[__ITER % ids.length];
  const currency = currencies[__ITER % currencies.length];
  const amount = amounts[currency].deposit;
  const res = postJson(
    '/api/v1/deposits',
    {
      requestId: requestId('dep'),
      accountId: accountId,
      balanceType: 'LIQUID',
      currency: currency,
      amount: amount,
    },
    'POST /api/v1/deposits',
  );
  check(res, {
    'deposit 200': (r) => r.status === 200,
  });
}

export function handleSummary(data) {
  return {
    stdout: writeSummary('deposit', data),
    [reportPath]: writeSummary('deposit', data),
  };
}

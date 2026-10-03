/**
 * Withdrawal stress with gas reserve + settle (same client account).
 *
 * 1) LIQUID → GAS_FEE (reserve)
 * 2) POST /withdrawals LIQUID → BANK (principal)
 * 3) GAS_FEE → BANK GAS_FEE (settle; may leave GAS_FEE negative)
 *
 * Setup deposits enough LIQUID for the run. Run after or independently of deposit.js.
 *
 * K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:9090/api/v1/write \
 * K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),avg \
 * k6 run -o experimental-prometheus-rw --tag testid=withdrawal-$(date +%s) k6/withdrawal.js
 */
import { check } from 'k6';
import {
  accountCount,
  accountsForVu,
  amounts,
  cashId,
  currencies,
  duration,
  ensureCashAccounts,
  postJson,
  requestId,
  vus,
  writeSummary,
} from './lib.js';

const reportPath = __ENV.REPORT_PATH || 'reports/latest/02-withdrawal.txt';
const fundRounds = Number(__ENV.FUND_ROUNDS || 30);

export const options = {
  setupTimeout: '30m',
  scenarios: {
    withdrawals: {
      executor: 'constant-vus',
      vus: vus,
      duration: duration,
    },
  },
  thresholds: {
    'http_req_failed{name:POST /api/v1/withdrawals}': ['rate==0'],
    'http_req_failed{name:POST /api/v1/postings}': ['rate==0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

export function setup() {
  ensureCashAccounts();
  // Seed LIQUID so each account can afford many reserve+withdraw+settle cycles.
  for (let i = 0; i < accountCount; i++) {
    const id = cashId(i);
    for (let c = 0; c < currencies.length; c++) {
      const currency = currencies[c];
      for (let n = 0; n < fundRounds; n++) {
        const res = postJson(
          '/api/v1/deposits',
          {
            requestId: `wd-fund-${id}-${currency}-${n}`,
            accountId: id,
            balanceType: 'LIQUID',
            currency: currency,
            amount: amounts[currency].deposit,
          },
          'POST /api/v1/deposits',
        );
        if (res.status !== 200) {
          throw new Error(`fund ${id} ${currency}: ${res.status} ${res.body}`);
        }
      }
    }
  }
  return { ok: true };
}

export default function () {
  const ids = accountsForVu(__VU);
  const accountId = ids[__ITER % ids.length];
  const currency = currencies[__ITER % currencies.length];
  const amt = amounts[currency];
  const ref = requestId('wd');

  const reserve = postJson(
    '/api/v1/postings',
    {
      requestId: `${ref}-reserve`,
      fromAccountId: accountId,
      fromBalanceType: 'LIQUID',
      toAccountId: accountId,
      toBalanceType: 'GAS_FEE',
      currency: currency,
      amount: amt.reserve,
      bizReference: ref,
      bizType: 'TRANSFER',
    },
    'POST /api/v1/postings',
  );
  const reserved = check(reserve, { 'reserve 200': (r) => r.status === 200 });
  if (!reserved) {
    return;
  }

  const withdraw = postJson(
    '/api/v1/withdrawals',
    {
      requestId: `${ref}-principal`,
      accountId: accountId,
      balanceType: 'LIQUID',
      currency: currency,
      amount: amt.withdraw,
    },
    'POST /api/v1/withdrawals',
  );
  const withdrawn = check(withdraw, { 'withdraw 200': (r) => r.status === 200 });
  if (!withdrawn) {
    return;
  }

  const settle = postJson(
    '/api/v1/postings',
    {
      requestId: `${ref}-settle`,
      fromAccountId: accountId,
      fromBalanceType: 'GAS_FEE',
      toAccountId: 'BANK',
      toBalanceType: 'GAS_FEE',
      currency: currency,
      amount: amt.settle,
      bizReference: ref,
      bizType: 'TRANSFER',
    },
    'POST /api/v1/postings',
  );
  check(settle, { 'settle 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return {
    stdout: writeSummary('withdrawal', data),
    [reportPath]: writeSummary('withdrawal', data),
  };
}

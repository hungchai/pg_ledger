/**
 * RFQ stress: 100 RFQ-* clients, one COMPANY dealer CO_RFQ (ETH/BTC/USDT LIQUID).
 * One atomic POST /api/v1/postings with two legs (quote + base).
 */
import { check } from 'k6';
import {
  duration,
  postJson,
  requestId,
  vus,
  writeSummary,
} from './lib.js';

const reportPath = __ENV.REPORT_PATH || 'reports/latest/03-rfq.txt';
const accountCount = Number(__ENV.RFQ_ACCOUNTS || __ENV.ACCOUNTS || 100);
const company = __ENV.RFQ_COMPANY || 'CO_RFQ';
const currencies = ['ETH', 'BTC', 'USDT'];

/** Large seed so long runs (e.g. 120m) do not exhaust LIQUID. */
const seed = {
  ETH: '10000.000000000000000000',
  BTC: '1000.00000000',
  USDT: '100000000.000000',
};

const rfqAmounts = {
  ETH: { base: '0.010000000000000000', quote: '36.500000' },
  BTC: { base: '0.00100000', quote: '73.000000' },
};

export const options = {
  setupTimeout: '30m',
  scenarios: {
    rfq: {
      executor: 'constant-vus',
      vus: vus,
      duration: duration,
    },
  },
  thresholds: {
    'http_req_failed{name:POST /api/v1/postings}': ['rate==0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

function rfqId(i) {
  return 'RFQ-' + String(i).padStart(3, '0');
}

function accountsForVu(vu) {
  const per = Math.floor(accountCount / vus);
  const start = (vu - 1) * per;
  const ids = [];
  for (let i = 0; i < per; i++) {
    ids.push(rfqId(start + i));
  }
  return ids;
}

function create(accountId, balanceType, currency, accountClass) {
  const res = postJson(
    '/api/v1/accounts',
    {
      accountId: accountId,
      balanceType: balanceType,
      currency: currency,
      name: accountId,
      accountClass: accountClass,
    },
    'POST /api/v1/accounts',
  );
  if (res.status !== 200 && res.status !== 422) {
    throw new Error(`create ${accountId} ${balanceType} ${currency}: ${res.status} ${res.body}`);
  }
}

function deposit(accountId, currency, amount, req) {
  const res = postJson(
    '/api/v1/deposits',
    {
      requestId: req,
      accountId: accountId,
      balanceType: 'LIQUID',
      currency: currency,
      amount: amount,
    },
    'POST /api/v1/deposits',
  );
  if (res.status !== 200) {
    throw new Error(`fund ${accountId} ${currency}: ${res.status} ${res.body}`);
  }
}

export function setup() {
  for (let c = 0; c < currencies.length; c++) {
    create(company, 'LIQUID', currencies[c], 'COMPANY');
  }
  for (let i = 0; i < accountCount; i++) {
    const id = rfqId(i);
    for (let c = 0; c < currencies.length; c++) {
      create(id, 'LIQUID', currencies[c], 'CLIENT');
    }
  }
  // Prefund company inventory + every client (LIQUID cannot go negative except BANK).
  for (let c = 0; c < currencies.length; c++) {
    const ccy = currencies[c];
    deposit(company, ccy, seed[ccy], `rfq-fund-${company}-${ccy}`);
  }
  for (let i = 0; i < accountCount; i++) {
    const id = rfqId(i);
    for (let c = 0; c < currencies.length; c++) {
      const ccy = currencies[c];
      deposit(id, ccy, seed[ccy], `rfq-fund-${id}-${ccy}`);
    }
  }
  return { company: company };
}

export default function (data) {
  const ids = accountsForVu(__VU);
  const client = ids[__ITER % ids.length];
  const pair = __ITER % 2 === 0 ? 'ETH' : 'BTC';
  const buy = Math.floor(__ITER / 2) % 2 === 0;
  const amt = rfqAmounts[pair];
  const ref = requestId('rfq');
  let legs;
  if (buy) {
    // client buys base with USDT
    legs = [
      {
        fromAccountId: client,
        fromBalanceType: 'LIQUID',
        toAccountId: data.company,
        toBalanceType: 'LIQUID',
        currency: 'USDT',
        amount: amt.quote,
      },
      {
        fromAccountId: data.company,
        fromBalanceType: 'LIQUID',
        toAccountId: client,
        toBalanceType: 'LIQUID',
        currency: pair,
        amount: amt.base,
      },
    ];
  } else {
    legs = [
      {
        fromAccountId: client,
        fromBalanceType: 'LIQUID',
        toAccountId: data.company,
        toBalanceType: 'LIQUID',
        currency: pair,
        amount: amt.base,
      },
      {
        fromAccountId: data.company,
        fromBalanceType: 'LIQUID',
        toAccountId: client,
        toBalanceType: 'LIQUID',
        currency: 'USDT',
        amount: amt.quote,
      },
    ];
  }
  const res = postJson(
    '/api/v1/postings',
    {
      requestId: ref,
      bizType: 'TRANSFER',
      bizReference: `${pair}USDT-${buy ? 'BUY' : 'SELL'}`,
      legs: legs,
    },
    'POST /api/v1/postings',
  );
  check(res, {
    'rfq 200': (r) => r.status === 200,
    'rfq two transfers': (r) => {
      if (r.status !== 200) {
        return false;
      }
      try {
        const body = JSON.parse(r.body);
        return Array.isArray(body) && body.length === 2;
      } catch (e) {
        return false;
      }
    },
  });
}

export function handleSummary(data) {
  return {
    stdout: writeSummary('rfq', data),
    [reportPath]: writeSummary('rfq', data),
  };
}

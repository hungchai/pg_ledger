import http from 'k6/http';

export const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
export const vus = Number(__ENV.VUS || 20);
export const duration = __ENV.DURATION || '60s';
export const accountCount = Number(__ENV.ACCOUNTS || 100);
export const currencies = ['ETH', 'BTC', 'USDT'];
export const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };

export const amounts = {
  ETH: {
    deposit: '0.100000000000000000',
    reserve: '0.001000000000000000',
    withdraw: '0.005000000000000000',
    settle: '0.002000000000000000',
  },
  BTC: {
    deposit: '0.10000000',
    reserve: '0.00100000',
    withdraw: '0.00500000',
    settle: '0.00200000',
  },
  USDT: {
    deposit: '100.000000',
    reserve: '1.000000',
    withdraw: '10.000000',
    settle: '2.000000',
  },
};

export function cashId(i) {
  return 'CASH-' + String(i).padStart(3, '0');
}

export function accountsForVu(vu) {
  const per = Math.floor(accountCount / vus);
  const start = (vu - 1) * per;
  const ids = [];
  for (let i = 0; i < per; i++) {
    ids.push(cashId(start + i));
  }
  return ids;
}

export function requestId(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

export function postJson(path, body, name) {
  return http.post(`${base}${path}`, JSON.stringify(body), {
    headers: headers,
    tags: { name: name },
  });
}

export function createAccount(accountId, balanceType, currency) {
  return postJson(
    '/api/v1/accounts',
    {
      accountId: accountId,
      balanceType: balanceType,
      currency: currency,
      name: accountId,
      accountClass: 'CLIENT',
    },
    'POST /api/v1/accounts',
  );
}

export function ensureCashAccounts() {
  for (let i = 0; i < accountCount; i++) {
    const id = cashId(i);
    for (let c = 0; c < currencies.length; c++) {
      const ccy = currencies[c];
      let res = createAccount(id, 'LIQUID', ccy);
      if (res.status !== 200 && res.status !== 422) {
        throw new Error(`create LIQUID ${id} ${ccy}: ${res.status} ${res.body}`);
      }
      res = createAccount(id, 'GAS_FEE', ccy);
      if (res.status !== 200 && res.status !== 422) {
        throw new Error(`create GAS_FEE ${id} ${ccy}: ${res.status} ${res.body}`);
      }
    }
  }
}

export function writeSummary(name, data) {
  const metrics = data.metrics || {};
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values ? metrics.http_reqs.values : {};
  const failed = metrics.http_req_failed && metrics.http_req_failed.values ? metrics.http_req_failed.values : {};
  const dur = metrics.http_req_duration && metrics.http_req_duration.values ? metrics.http_req_duration.values : {};
  const checks = metrics.checks && metrics.checks.values ? metrics.checks.values : {};
  const lines = [];
  lines.push(`# pgledger ${name}`);
  lines.push(`generated_at: ${new Date().toISOString()}`);
  lines.push(`base_url: ${base}`);
  lines.push(`vus: ${vus}`);
  lines.push(`duration: ${__ENV.DURATION || '60s'}`);
  lines.push(`accounts: ${accountCount}`);
  lines.push(`http_reqs: ${httpReqs.count != null ? httpReqs.count : 'n/a'}`);
  lines.push(`tps: ${httpReqs.rate != null ? Number(httpReqs.rate).toFixed(2) : 'n/a'}`);
  lines.push(`error_rate: ${failed.rate != null ? Number(failed.rate).toFixed(6) : 'n/a'}`);
  lines.push(`checks_rate: ${checks.rate != null ? Number(checks.rate).toFixed(6) : 'n/a'}`);
  lines.push(`p50_ms: ${dur['p(50)'] != null ? Number(dur['p(50)']).toFixed(2) : 'n/a'}`);
  lines.push(`p95_ms: ${dur['p(95)'] != null ? Number(dur['p(95)']).toFixed(2) : 'n/a'}`);
  lines.push(`p99_ms: ${dur['p(99)'] != null ? Number(dur['p(99)']).toFixed(2) : 'n/a'}`);
  lines.push(`avg_ms: ${dur.avg != null ? Number(dur.avg).toFixed(2) : 'n/a'}`);
  lines.push('');
  lines.push('## thresholds');
  const th = data.root_group && data.root_group.checks ? data.root_group.checks : [];
  for (let i = 0; i < th.length; i++) {
    const c = th[i];
    lines.push(`- ${c.name}: pass=${c.passes} fail=${c.fails}`);
  }
  if (data.options && data.options.thresholds) {
    lines.push('');
    lines.push('## configured thresholds');
    const keys = Object.keys(data.metrics);
    for (let i = 0; i < keys.length; i++) {
      const m = data.metrics[keys[i]];
      if (m && m.thresholds) {
        const names = Object.keys(m.thresholds);
        for (let j = 0; j < names.length; j++) {
          const t = m.thresholds[names[j]];
          lines.push(`- ${keys[i]}{${names[j]}}: ${t.ok ? 'ok' : 'FAILED'}`);
        }
      }
    }
  }
  return lines.join('\n') + '\n';
}

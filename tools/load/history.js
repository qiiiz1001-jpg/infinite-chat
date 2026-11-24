import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://localhost:10010').replace(/\/$/, '');
const peak = Number(__ENV.VUS || 10);
const success = new Rate('history_business_success');
const latency = new Trend('history_latency', true);
export const options = {
  stages: [{ duration: '15s', target: 1 }, { duration: '30s', target: peak },
    { duration: '60s', target: peak }, { duration: '15s', target: 0 }],
  thresholds: {
    history_business_success: ['rate>0.99'],
    history_latency: [`p(95)<${__ENV.P95_MS || 500}`, `p(99)<${__ENV.P99_MS || 1000}`],
  },
};
export function setup() {
  const response = http.post(`${base}/api/user/login/password`, JSON.stringify({
    email: __ENV.EMAIL || 'demo-a@example.com', password: __ENV.PASSWORD || '123456',
  }), { headers: { 'Content-Type': 'application/json' }, timeout: '15s', tags: { name: 'login' } });
  let body;
  try { body = response.json(); } catch (_) { fail('Login returned invalid JSON'); }
  if (response.status !== 200 || body.code !== 200 || !body.data.accessToken) fail('Login failed');
  // One login for HTTP-only readers; logging in this account elsewhere may invalidate this token.
  return { token: body.data.accessToken, before: Date.now() + 1 };
}
export default function (data) {
  const response = http.post(`${base}/api/message/history`, JSON.stringify({
    sessionId: __ENV.SESSION_ID || '99001', beforeTime: data.before, limit: 20,
  }), { headers: { 'Content-Type': 'application/json', 'Access-Token': data.token },
    timeout: '15s', tags: { name: 'history' } });
  let body;
  try { body = response.json(); } catch (_) { body = null; }
  const ok = response.status === 200 && body && body.code === 200 && Array.isArray(body.data);
  success.add(!!ok);
  latency.add(response.timings.duration);
  check(response, { 'HTTP and business result succeed': () => !!ok });
  sleep(Number(__ENV.THINK_SECONDS || 0.2));
}

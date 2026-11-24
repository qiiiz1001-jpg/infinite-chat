import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://localhost:10010').replace(/\/$/, '');
const accounts = JSON.parse(open(__ENV.ACCOUNTS || './accounts.example.json'));
const count = Number(__ENV.VUS || 2);
const holdMs = Number(__ENV.HOLD_SECONDS || 60) * 1000;
if (!Number.isInteger(count) || count < 1 || count > accounts.length) throw new Error('VUS exceeds account count');
if (new Set(accounts.map(a => a.email)).size !== accounts.length) throw new Error('Use distinct accounts');
if (!Number.isFinite(holdMs) || holdMs < 1000) throw new Error('Invalid HOLD_SECONDS');
const healthy = new Rate('ws_connection_healthy');
const pingTime = new Trend('application_ping_ms', true);
export const options = {
  setupTimeout: '10m',
  scenarios: { connections: { executor: 'per-vu-iterations', vus: count, iterations: 1,
    maxDuration: `${Math.ceil(holdMs / 1000) + 60}s` } },
  thresholds: { ws_connection_healthy: ['rate>0.99'] },
};
export function setup() {
  return accounts.slice(0, count).map(account => {
    const response = http.post(`${base}/api/user/login/password`, JSON.stringify(account), {
      headers: { 'Content-Type': 'application/json' }, timeout: '15s', tags: { name: 'login' },
    });
    let body;
    try { body = response.json(); } catch (_) { fail('Invalid login response'); }
    if (response.status !== 200 || body.code !== 200 || !body.data.accessToken || !body.data.nettyUri)
      fail('Login failed; check test fixtures and Nacos readiness');
    return { token: body.data.accessToken, uri: body.data.nettyUri };
  });
}
export default function (data) {
  const account = data[__VU - 1];
  let opened = 0, pongs = 0, lastPing = 0;
  const response = ws.connect(account.uri, {
    headers: { Authorization: `Bearer ${account.token}` }, tags: { name: 'ws-idle' },
  }, socket => {
    socket.on('open', () => {
      opened = Date.now();
      lastPing = Date.now();
      socket.send('ping');
      socket.setInterval(() => { lastPing = Date.now(); socket.send('ping'); }, 15000);
      socket.setTimeout(() => socket.close(), holdMs);
    });
    socket.on('message', message => {
      if (message === 'pong') { pongs++; pingTime.add(Date.now() - lastPing); }
    });
    socket.on('error', () => {}); // Metrics record failure; do not print credentials or URL query data.
  });
  const ok = !!response && response.status === 101 && opened > 0
    && Date.now() - opened >= holdMs - 500 && pongs >= Math.max(1, Math.floor(holdMs / 15000));
  healthy.add(ok);
  check(response, { 'connection stays open and receives application pong': () => ok });
}

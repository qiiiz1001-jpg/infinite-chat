import { reactive } from 'vue'

export const state = reactive({ user: loadIdentity(), generation: 0 })

function loadIdentity() {
    try { return JSON.parse(sessionStorage.getItem('qingliao.identity')); }
    catch { return null; }
}
export function identity(user) {
    state.generation++;
    state.user = user;
    if (user) sessionStorage.setItem('qingliao.identity', JSON.stringify(user));
    else sessionStorage.removeItem('qingliao.identity');
}
export function parseWire(raw) {
    return JSON.parse(raw, (_, value, context) =>
        typeof value === 'number' && Number.isInteger(value) && !Number.isSafeInteger(value) && /^-?\d+$/.test(context?.source)
            ? context.source : value);
}
export async function api(path, options = {}) {
    const { method = 'GET', body, plain = false, auth = true } = options;
    const epoch = state.generation;
    const token = auth ? state.user?.accessToken : null;
    const headers = token ? { 'Access-Token': token } : {};
    const form = body instanceof FormData;
    if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, { method, headers, body: form || body === undefined ? body : JSON.stringify(body) });
    const raw = await response.text();
    if (auth && epoch !== state.generation) throw Error('登录状态已变化。');
    let result;
    try { result = parseWire(raw); } catch { result = null; }
    if (token && (response.status === 401 || Number(result?.code) === 40100)) {
        document.dispatchEvent(new Event('qingliao:expired'));
        throw Error('登录已过期，请重新登录。');
    }
    if (!response.ok || result?.code && Number(result.code) !== 200) {
        throw Error(result?.message || '请求失败，请稍后重试。');
    }
    if (plain) return raw;
    if (result === null) throw Error('服务返回了无法读取的结果。');
    return Object.hasOwn(result, 'data') ? result.data : result;
}
export const post = (path, body) => api(path, { method: 'POST', body });
export const id = value => String(value ?? '');
export const encode = value => encodeURIComponent(id(value));

export function localDate(value) {
    if (!value) return '';
    const stamp = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value;
    const date = new Date(stamp);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false });
}
export function dateInput(value) {
    if (!value) return '';
    const when = new Date(Number(value));
    return new Date(when.getTime() - when.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
export function currentUser() { return id(state.user?.userId); }

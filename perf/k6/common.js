export const BASE_URL   = __ENV.NYX_BASE_URL   || 'http://localhost:8080';
export const AUTH_TOKEN = __ENV.NYX_AUTH_TOKEN || '';

export function defaultHeaders() {
    const h = {};
    if (AUTH_TOKEN) h['Authorization'] = `Bearer ${AUTH_TOKEN}`;
    return h;
}

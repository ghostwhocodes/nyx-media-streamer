import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, defaultHeaders } from './common.js';

export const options = {
    vus: 100,
    duration: '30s',
    thresholds: {
        'http_req_failed':   ['rate<0.01'],   // <1% errors
        'http_req_duration': ['p(95)<2000'],  // p95 < 2 s
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/api/v1/audio/files`, { headers: defaultHeaders() });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.1);
}

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, defaultHeaders } from './common.js';

export const options = {
    vus: 10,
    duration: '60s',
    thresholds: {
        'http_req_failed':   ['rate<0.01'],
        'http_req_duration': ['p(95)<500'],   // job listing should be fast
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/api/v1/transcode/jobs`, { headers: defaultHeaders() });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.2);
}

// soak — 시간에 따른 열화 관측 (PROJECT_BRIEF 7장)
//
// 검증 대상: NFR-06
// 1단계 전용.
//
// 30분 내내 발급 성공 경로가 유지되어야 열화 추세를 볼 수 있으므로
// 소진되지 않는 쿠폰을 쓴다. (확정값 D-03)
//
// 실행:  k6 run loadtest/scenarios/soak.js

import { issueOnce, warmupOnce } from '../lib/issue.js';
import { SUSTAINED_COUPON_ID } from '../lib/config.js';

const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || '30m';

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        soak: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
            startTime: '35s',
            exec: 'measure',
            tags: { phase: 'measure' },
        },
    },
    thresholds: {
        'http_req_duration{phase:measure,name:issue}': [`p(99)<${__ENV.P99_MS || 500}`],
        'coupon_server_error': ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function warmup() {
    warmupOnce(SUSTAINED_COUPON_ID);
}

export function measure() {
    issueOnce(SUSTAINED_COUPON_ID);
}

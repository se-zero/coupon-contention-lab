// ramp — 꺾이는 지점 탐색 (PROJECT_BRIEF 7장)
//
// 검증 대상: NFR-03
// 1단계 전용.
//
// 재고가 소진되지 않는 쿠폰을 쓴다. 측정 도중 소진되면 워크로드가 '발급'에서 '거절'로
// 바뀌어 동시성 구간 간 비교가 깨진다.
//
// 실행:  k6 run loadtest/scenarios/ramp.js

import { issueOnce, warmupOnce } from '../lib/issue.js';
import { SUSTAINED_COUPON_ID } from '../lib/config.js';

const HOLD = __ENV.HOLD || '2m';

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        // 200 -> 500 -> 1000 -> 2000 계단식 증가 (PROJECT_BRIEF 5.3)
        ramp: {
            executor: 'ramping-vus',
            startVUs: 0,
            startTime: '35s',
            exec: 'measure',
            tags: { phase: 'measure' },
            gracefulRampDown: '10s',
            stages: [
                { duration: '20s', target: 200 },  { duration: HOLD, target: 200 },
                { duration: '20s', target: 500 },  { duration: HOLD, target: 500 },
                { duration: '20s', target: 1000 }, { duration: HOLD, target: 1000 },
                { duration: '20s', target: 2000 }, { duration: HOLD, target: 2000 },
                { duration: '20s', target: 0 },
            ],
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

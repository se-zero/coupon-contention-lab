// spike — 선착순 상황 재현 (PROJECT_BRIEF 7장)
//
// 검증 대상: NFR-01 ~ NFR-04
// 1·2단계 모두에서 사용한다.
//
// 실행:  k6 run loadtest/scenarios/spike.js
//        k6 run -e VUS=2000 loadtest/scenarios/spike.js

import { issueOnce, warmupOnce } from '../lib/issue.js';
import { SPIKE_COUPON_ID, SUSTAINED_COUPON_ID } from '../lib/config.js';

const VUS = Number(__ENV.VUS || 1000);
// 재고 100,000 의 10배 (확정값 D-02) — 소진 후 900,000 건이 NFR-04 검증 구간이 된다
const TOTAL = Number(__ENV.TOTAL || 1000000);

export const options = {
    scenarios: {
        // 워밍업 — 다른 쿠폰을 쓰므로 spike-coupon 재고를 건드리지 않는다
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        // 측정 — 전 VU 동시 기동으로 순간 집중을 만든다
        spike: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: TOTAL,
            maxDuration: __ENV.MAX_DURATION || '60m',
            startTime: '35s',
            exec: 'measure',
            tags: { phase: 'measure' },
        },
    },
    thresholds: {
        // NFR-03 — 워밍업 구간을 제외한 측정 구간만 판정한다
        'http_req_duration{phase:measure,name:issue}': [`p(99)<${__ENV.P99_MS || 500}`],
        // NFR-04 — 5xx 는 1건도 허용하지 않는다
        'coupon_server_error': ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function warmup() {
    warmupOnce(SUSTAINED_COUPON_ID);
}

export function measure() {
    issueOnce(SPIKE_COUPON_ID);
}

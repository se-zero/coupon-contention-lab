// ramp — 꺾이는 지점 탐색 (PROJECT_BRIEF 7장)
//
// 검증 대상: NFR-03
// 1단계 전용.
//
// 재고가 소진되지 않는 쿠폰을 쓴다. 측정 도중 소진되면 워크로드가 '발급'에서 '거절'로
// 바뀌어 동시성 구간 간 비교가 깨진다.
//
// 실행:  k6 run loadtest/scenarios/ramp.js

import exec from 'k6/execution';
import { issueOnce, warmupOnce } from '../lib/issue.js';
import { SUSTAINED_COUPON_ID } from '../lib/config.js';

const HOLD = __ENV.HOLD || '2m';
const P99 = `p(99)<${__ENV.P99_MS || 500}`;

// 계단 정의 — stages 와 stageOf() 가 같은 값을 본다
const TARGETS = [200, 500, 1000, 2000];
const RAMP_SEC = 20;
const HOLD_SEC = parseSeconds(HOLD);

function parseSeconds(d) {
    const m = /^(?:(\d+)m)?(?:(\d+)s)?$/.exec(d);
    if (!m) throw new Error(`HOLD 형식은 2m / 30s / 1m30s 여야 한다: ${d}`);
    return Number(m[1] || 0) * 60 + Number(m[2] || 0);
}

// 지금이 어느 계단인지 — 계단별 P99 를 따로 남기기 위한 태그 (브리프 3.3 "동시성 N 부터 미달" 형태)
// 상승 구간(20초)은 transition 으로 분리해 판정에서 제외한다
function stageOf() {
    const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
    for (let i = 0; i < TARGETS.length; i++) {
        const rampEnd = i * (RAMP_SEC + HOLD_SEC) + RAMP_SEC;
        if (elapsed < rampEnd) return 'transition';
        if (elapsed < rampEnd + HOLD_SEC) return `vu${TARGETS[i]}`;
    }
    return 'transition';  // 마지막 하강 구간
}

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
            stages: TARGETS.flatMap(t => [
                { duration: `${RAMP_SEC}s`, target: t }, { duration: HOLD, target: t },
            ]).concat([{ duration: `${RAMP_SEC}s`, target: 0 }]),
        },
    },
    thresholds: {
        'http_req_duration{phase:measure,name:issue}': [P99],
        // 계단별 — 임계값에 올려야 요약 JSON 에 계단별 P99 가 따로 떨어진다
        'http_req_duration{stage:vu200}': [P99],
        'http_req_duration{stage:vu500}': [P99],
        'http_req_duration{stage:vu1000}': [P99],
        'http_req_duration{stage:vu2000}': [P99],
        'coupon_server_error': ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function warmup() {
    warmupOnce(SUSTAINED_COUPON_ID);
}

export function measure() {
    issueOnce(SUSTAINED_COUPON_ID, { stage: stageOf() });
}

// chaos — 부하 중 Redis 강제 종료 (PROJECT_BRIEF 7장, NFR-05)
//
// 검증 대상: NFR-05. W3 / W4 만 대상 — 나머지는 Redis 를 쓰지 않는다.
// 부하 자체는 spike 와 같다. Redis 를 죽이고 살리는 것은 k6 가 아니라 run-experiment.ps1 이 한다:
// 발급 50,000건 시점에 kill, 10초 뒤 빈 상태로 재시작 (확정값 D-07).
//
// 임계값을 두지 않는다. 정전 구간의 5xx 와 P99 는 예상된 결과라 임계값을 두면 매번 FAIL 이 찍혀
// 의미가 없다. 판정은 실행 후 정합성(verify.sql)과 "k6 발급 수 - DB 행 수" 로 한다.
//
// 실행:  .\run-experiment.ps1 -Strategy W4 -Scenario chaos -Run 1

import { issueOnce, warmupOnce } from '../lib/issue.js';
import { SPIKE_COUPON_ID, SUSTAINED_COUPON_ID } from '../lib/config.js';

const VUS = Number(__ENV.VUS || 1000);
const TOTAL = Number(__ENV.TOTAL || 150000);

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        chaos: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: TOTAL,
            maxDuration: __ENV.MAX_DURATION || '60m',
            startTime: '35s',
            exec: 'measure',
            tags: { phase: 'measure' },
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function warmup() {
    warmupOnce(SUSTAINED_COUPON_ID);
}

export function measure() {
    issueOnce(SPIKE_COUPON_ID);
}

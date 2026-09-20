// stampede — TTL 만료 순간의 캐시 스탬피드 재현 (docs/cache-stampede.md 7장)
//
// 검증 대상: 스탬피드 재현 자체 — NFR 판정은 없다 (읽기 부하, 브리프 3.4)
// 실험 B 전용. 단일 인스턴스.
//
// 세 구간 (D-12):
//   jit      constant-vus, sustained-coupon 만 조회 — JVM/JIT·커넥션 풀 워밍업.
//            실험 대상 20개 키는 일부러 건드리지 않는다 — 여기서 캐시가 채워지면
//            TTL 시계가 이 구간(기본 30초)에 걸쳐 흩어져 동시 만료가 깨진다 (cache-stampede.md 3-2)
//   warm     vus 1, iterations 20 — 20개 키를 한 번씩 순서대로 조회해 캐시를 채운다.
//            1 VU 로 수백 ms 안에 끝나므로 TTL 시계가 거의 같은 순간에 시작한다 — 이것이 C1 의 동시 만료를 만든다
//   measure  constant-vus, 20개 키 균등 랜덤 조회 — 측정 구간
//
// 만료 시각 계산 (기본값 기준): warm 이 WARM_START(35s) 부근에서 전 키를 채우므로 첫 만료는 t≈95s(=35+TTL 60).
// 측정 구간이 MEASURE_START(40s) ~ 340s(5분) 이므로 60초 간격으로 95, 155, 215, 275, 335 — 5번 온다.
//
// VUS·DURATION(측정 구간)과 WARM_START·MEASURE_START(TTL 시계 기준 시각)는 __ENV 로 덮어쓸 수 있다 (축소 실행용).
//
// 실행:  k6 run loadtest/scenarios/stampede.js
//        k6 run -e VUS=20 -e DURATION=40s loadtest/scenarios/stampede.js   (축소 실행)

import exec from 'k6/execution';
import { readOnce, randomCacheCouponId } from '../lib/read.js';
import { SUSTAINED_COUPON_ID, CACHE_COUPON_START, CACHE_COUPON_COUNT } from '../lib/config.js';

const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || '5m';
const WARM_START = __ENV.WARM_START || '35s';
const MEASURE_START = __ENV.MEASURE_START || '40s';

export const options = {
    scenarios: {
        jit: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'jit',
            tags: { phase: 'jit' },
        },
        warm: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: CACHE_COUPON_COUNT,
            startTime: WARM_START,
            exec: 'warm',
            tags: { phase: 'warm' },
        },
        measure: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
            startTime: MEASURE_START,
            exec: 'measure',
            tags: { phase: 'measure' },
        },
    },
    thresholds: {
        'coupon_read_error': ['count==0'],
        // 참고값 — 판정 아님. C0~C4 비교는 db_load 피크로 한다 (docs/cache-stampede.md 3-5)
        'http_req_duration{phase:measure,name:read}': [`p(99)<${__ENV.P99_MS || 500}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function jit() {
    readOnce(SUSTAINED_COUPON_ID);
}

export function warm() {
    // 1 VU 이므로 iterationInTest 가 0..19 를 순서대로 준다 — 키 101..120 을 한 번씩
    readOnce(CACHE_COUPON_START + exec.scenario.iterationInTest);
}

export function measure() {
    readOnce(randomCacheCouponId());
}

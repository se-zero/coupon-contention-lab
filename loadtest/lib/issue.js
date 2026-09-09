// 발급 요청 공통 모듈
//
// 모든 시나리오가 이 함수 하나만 쓴다. 시나리오별로 요청 형태가 달라지면 비교가 무의미해진다.

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL } from './config.js';

// 비즈니스 거절(4xx)은 실패가 아니다. 5xx 만 실패로 집계한다 (NFR-04)
http.setResponseCallback(http.expectedStatuses(201, 400, 404, 409));

// 워밍업 구간이 측정 구간의 user_id 와 겹치지 않도록 분리한다
const WARMUP_USER_OFFSET = 900000000;

export const issued = new Counter('coupon_issued');
export const soldOut = new Counter('coupon_sold_out');
export const duplicated = new Counter('coupon_duplicated');
export const serverError = new Counter('coupon_server_error');

// 전역 유니크 user_id — 1인 1매 제약(NFR-02)과 충돌하지 않게 한다
function nextUserId(offset) {
    return offset + exec.scenario.iterationInTest + 1;
}

function post(couponId, userId) {
    const res = http.post(
        `${BASE_URL}/api/coupons/${couponId}/issue`,
        JSON.stringify({ userId }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'issue' } },
    );

    if (res.status >= 500) {
        serverError.add(1);
        return;
    }

    const result = res.json('result');
    if (result === 'ISSUED') issued.add(1);
    else if (result === 'SOLD_OUT') soldOut.add(1);
    else if (result === 'DUPLICATE') duplicated.add(1);
}

// 측정 구간 발급
export function issueOnce(couponId) {
    post(couponId, nextUserId(0));
}

// 워밍업 구간 발급 (JIT 컴파일 유도, 측정에서 제외)
export function warmupOnce(couponId) {
    post(couponId, nextUserId(WARMUP_USER_OFFSET));
}

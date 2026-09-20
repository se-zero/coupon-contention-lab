// 조회 요청 공통 모듈
//
// 모든 읽기 요청이 이 함수 하나만 쓴다. 시나리오별로 요청 형태가 달라지면 비교가 무의미해진다.

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, CACHE_COUPON_START, CACHE_COUPON_COUNT } from './config.js';

// 404 는 실패가 아니다
http.setResponseCallback(http.expectedStatuses(200, 404));

export const readOk = new Counter('coupon_read_ok');
export const readError = new Counter('coupon_read_error');

// 단건 조회
export function readOnce(couponId) {
    const res = http.get(`${BASE_URL}/api/coupons/${couponId}`, { tags: { name: 'read' } });

    if (res.status >= 500) {
        readError.add(1);
        return;
    }
    readOk.add(1);
}

// 실험 대상 20개 키 중 균등 랜덤 선택 (D-12)
export function randomCacheCouponId() {
    return CACHE_COUPON_START + Math.floor(Math.random() * CACHE_COUPON_COUNT);
}

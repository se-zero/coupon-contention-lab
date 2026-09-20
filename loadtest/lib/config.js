// 부하 스크립트 공통 설정

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 재고 100,000 — 소진되어야 NFR-01/02/04 를 검증할 수 있다 (확정값 D-02)
export const SPIKE_COUPON_ID = Number(__ENV.SPIKE_COUPON_ID || 1);

// 재고 20,000,000 — 측정 중 소진되지 않는다 (ramp/soak 용, 확정값 D-03)
export const SUSTAINED_COUPON_ID = Number(__ENV.SUSTAINED_COUPON_ID || 2);

// 실험 B 전용 쿠폰 id 101~120 (D-12: 키 20개)
export const CACHE_COUPON_START = Number(__ENV.CACHE_COUPON_START || 101);
export const CACHE_COUPON_COUNT = Number(__ENV.CACHE_COUPON_COUNT || 20);

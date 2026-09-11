package com.experiment.coupon.domain;

import org.springframework.http.HttpStatus;

/**
 * 발급 결과
 *
 * 응답 포맷과 상태 코드는 모든 전략이 공유한다. (PROJECT_BRIEF 5.4 공정성)
 * 비즈니스 거절은 4xx 로 내려간다. 5xx 는 NFR-04 위반이므로 여기에 두지 않는다.
 */
public enum IssueResult {

    ISSUED(HttpStatus.CREATED),              // 발급 성공
    SOLD_OUT(HttpStatus.CONFLICT),           // 재고 소진 (FR-02)
    DUPLICATE(HttpStatus.CONFLICT),          // 1인 1매 위반 (NFR-02)
    CONFLICT(HttpStatus.CONFLICT),           // 낙관적 충돌 — 재시도 소진 (W2)
    NOT_IN_PERIOD(HttpStatus.BAD_REQUEST),   // 발급 기간 외 (FR-05)
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND);  // 쿠폰 없음

    private final HttpStatus status;

    IssueResult(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}

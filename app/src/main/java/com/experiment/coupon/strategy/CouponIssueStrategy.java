package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.IssueResult;

/**
 * 재고 차감 전략 (W0 ~ W4)
 *
 * 비교군 간 차이는 오직 동시성 제어 방식뿐이어야 한다. (PROJECT_BRIEF 5.4)
 * 검증 로직은 IssueValidator, 실제 차감/저장은 IssueCore 를 공유한다.
 */
public interface CouponIssueStrategy {

    // 발급 시도
    IssueResult issue(long couponId, long userId);

    // 전략 식별자 ("W0" ~ "W4")
    String type();
}

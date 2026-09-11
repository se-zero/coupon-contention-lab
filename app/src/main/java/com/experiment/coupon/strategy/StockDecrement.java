package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.Coupon;

/**
 * 재고 차감 방식
 *
 * 전략마다 달라지는 유일한 지점. 조회·검증·중복 확인·저장은 IssueCore 가 공유한다. (PROJECT_BRIEF 5.4)
 */
@FunctionalInterface
public interface StockDecrement {

    // 차감 성공 여부 (낙관적 충돌 시 false)
    boolean apply(Coupon coupon);
}

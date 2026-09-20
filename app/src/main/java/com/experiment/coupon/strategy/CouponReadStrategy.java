package com.experiment.coupon.strategy;

import com.experiment.coupon.api.dto.CouponResponse;

import java.util.Optional;

/**
 * 조회 전략 (C0 ~ C4)
 *
 * 비교군 간 차이는 오직 캐시 전략뿐이어야 한다. (PROJECT_BRIEF 5.4)
 * 응답 포맷은 CouponQueryService 와 동일한 CouponResponse 를 그대로 쓴다.
 */
public interface CouponReadStrategy {

    // 쿠폰 상세 조회
    Optional<CouponResponse> findById(long couponId);

    // 전략 식별자 ("C0" ~ "C4")
    String type();
}

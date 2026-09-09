package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.Coupon;

import java.time.LocalDateTime;

/**
 * 공통 검증
 *
 * 전략별로 검증이 달라지면 비교가 무의미해지므로 여기 한 곳에만 둔다. (PROJECT_BRIEF 5.4)
 */
public final class IssueValidator {

    private IssueValidator() {
    }

    // 발급 기간 검증 (FR-05)
    public static boolean outOfPeriod(Coupon coupon, LocalDateTime now) {
        return now.isBefore(coupon.getStartAt()) || now.isAfter(coupon.getEndAt());
    }

    // 재고 소진 검증 (FR-02)
    public static boolean soldOut(Coupon coupon) {
        return coupon.getIssuedCount() >= coupon.getTotalQuantity();
    }
}

package com.experiment.coupon.api.dto;

import com.experiment.coupon.domain.Coupon;

import java.time.LocalDateTime;

/**
 * 쿠폰 조회 응답 (FR-03, FR-04)
 */
public record CouponResponse(
        Long id,
        String name,
        int totalQuantity,
        int issuedCount,
        int remaining,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getName(),
                coupon.getTotalQuantity(),
                coupon.getIssuedCount(),
                coupon.remaining(),
                coupon.getStartAt(),
                coupon.getEndAt());
    }
}

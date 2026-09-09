package com.experiment.coupon.api.dto;

import com.experiment.coupon.domain.IssueResult;

/**
 * 발급 응답
 *
 * 전 전략 동일 포맷. strategy/instance 는 2단계 다중 인스턴스 검증용 식별자다.
 */
public record IssueResponse(
        String result,
        Long couponId,
        Long userId,
        String strategy,
        String instance
) {
    public static IssueResponse of(IssueResult result, long couponId, long userId,
                                   String strategy, String instance) {
        return new IssueResponse(result.name(), couponId, userId, strategy, instance);
    }
}

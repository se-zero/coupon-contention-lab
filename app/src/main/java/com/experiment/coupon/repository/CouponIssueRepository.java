package com.experiment.coupon.repository;

import com.experiment.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    // 1인 1매 사전 확인 (NFR-02)
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    // 정합성 검증용 실제 발급 건수
    long countByCouponId(Long couponId);
}

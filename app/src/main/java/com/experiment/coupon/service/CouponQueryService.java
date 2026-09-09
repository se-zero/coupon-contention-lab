package com.experiment.coupon.service;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.repository.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 조회 (FR-03, FR-04)
 *
 * 실험 B 의 C0(캐시 없음) 기준선이다. 캐시 계층은 3주차에 이 앞단에 붙는다.
 */
@Service
public class CouponQueryService {

    private final CouponRepository couponRepository;

    public CouponQueryService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    // 쿠폰 상세 (FR-03)
    @Transactional(readOnly = true)
    public Optional<CouponResponse> findById(long couponId) {
        return couponRepository.findById(couponId).map(CouponResponse::from);
    }

    // 쿠폰 목록 (FR-04)
    @Transactional(readOnly = true)
    public List<CouponResponse> findAll() {
        return couponRepository.findAll().stream().map(CouponResponse::from).toList();
    }
}

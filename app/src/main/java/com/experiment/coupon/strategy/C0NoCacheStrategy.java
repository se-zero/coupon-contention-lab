package com.experiment.coupon.strategy;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.metrics.CacheMetrics;
import com.experiment.coupon.repository.CouponRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * C0 — 캐시 없음 (기준선)
 *
 * CouponQueryService.findById 와 같은 경로로 매 요청 DB 를 읽는다.
 * 전부 미스이자 db_load 다 — 히트는 존재하지 않는다. (docs/cache-basics.md 4장)
 */
@Component
public class C0NoCacheStrategy implements CouponReadStrategy {

    private final CouponRepository couponRepository;
    private final CacheMetrics metrics;

    public C0NoCacheStrategy(CouponRepository couponRepository, CacheMetrics metrics) {
        this.couponRepository = couponRepository;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CouponResponse> findById(long couponId) {
        metrics.recordMiss();
        long start = System.nanoTime();
        Optional<CouponResponse> result = couponRepository.findById(couponId).map(CouponResponse::from);
        metrics.recordDbLoad(Duration.ofNanos(System.nanoTime() - start));
        return result;
    }

    @Override
    public String type() {
        return "C0";
    }
}

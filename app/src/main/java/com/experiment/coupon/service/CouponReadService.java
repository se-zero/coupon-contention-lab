package com.experiment.coupon.service;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.strategy.CouponReadStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 조회 진입점 (실험 B, 상세 조회 전용)
 *
 * 전략 호출만 담당한다. 전략별 분기를 여기에 두지 않는다. (PROJECT_BRIEF 5.4)
 * 활성 전략은 coupon.cache 설정값으로 교체한다. (CouponIssueService 와 같은 구조)
 */
@Service
public class CouponReadService {

    private static final Logger log = LoggerFactory.getLogger(CouponReadService.class);

    private final CouponReadStrategy strategy;

    public CouponReadService(List<CouponReadStrategy> strategies,
                             @Value("${coupon.cache}") String type) {
        this.strategy = select(strategies, type);
        log.info("active read strategy = {}", this.strategy.type());
    }

    // 설정값에 대응하는 전략 선택 (없으면 기동 실패)
    private static CouponReadStrategy select(List<CouponReadStrategy> strategies, String type) {
        return strategies.stream()
                .filter(s -> s.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "unknown cache strategy: " + type + ", available=" + strategies.stream().map(CouponReadStrategy::type).toList()));
    }

    public Optional<CouponResponse> findById(long couponId) {
        return strategy.findById(couponId);
    }

    // 활성 전략 식별자
    public String strategyType() {
        return strategy.type();
    }
}

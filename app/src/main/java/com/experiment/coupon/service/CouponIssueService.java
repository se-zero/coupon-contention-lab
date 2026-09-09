package com.experiment.coupon.service;

import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.metrics.IssueMetrics;
import com.experiment.coupon.strategy.CouponIssueStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 발급 진입점
 *
 * 전략 호출 + 메트릭 집계만 담당한다. 전략별 분기를 여기에 두지 않는다. (PROJECT_BRIEF 5.4)
 * 활성 전략은 coupon.strategy 설정값으로 교체한다.
 */
@Service
public class CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueService.class);

    private final CouponIssueStrategy strategy;
    private final IssueMetrics metrics;

    public CouponIssueService(List<CouponIssueStrategy> strategies,
                              IssueMetrics metrics,
                              @Value("${coupon.strategy}") String type) {
        this.strategy = select(strategies, type);
        this.metrics = metrics;
        log.info("active issue strategy = {}", this.strategy.type());
    }

    // 설정값에 대응하는 전략 선택 (없으면 기동 실패)
    private static CouponIssueStrategy select(List<CouponIssueStrategy> strategies, String type) {
        return strategies.stream()
                .filter(s -> s.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "unknown strategy: " + type + ", available=" + strategies.stream().map(CouponIssueStrategy::type).toList()));
    }

    public IssueResult issue(long couponId, long userId) {
        IssueResult result;
        try {
            result = strategy.issue(couponId, userId);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 최후 방어선 작동 — 전략이 막지 못한 중복
            metrics.recordConstraintViolation();
            result = IssueResult.DUPLICATE;
        }
        metrics.recordResult(result);
        return result;
    }

    // 활성 전략 식별자
    public String strategyType() {
        return strategy.type();
    }
}

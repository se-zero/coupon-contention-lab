package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.metrics.IssueMetrics;
import com.experiment.coupon.repository.CouponRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * W2 — 낙관적 락 (version 조건부 UPDATE + 재시도)
 *
 * 예상 실패 모드: 동시성이 높을수록 재시도 폭주, 실패율 급증 (PROJECT_BRIEF 5.1)
 * 쿠폰 행 하나를 전원이 경합하므로 낙관적 락이 유리한 조건의 정반대다.
 * 이는 결함이 아니라 관측 대상이므로 고치지 않는다. (작업 규칙 4)
 *
 * Coupon 에 @Version 을 붙이지 않는 이유는 5.2 참조 — 전 전략에 암묵 적용되면
 * W0 의 다중 인스턴스 붕괴가 가려진다.
 */
@Component
public class W2OptimisticLockStrategy implements CouponIssueStrategy {

    // 재시도 한계 (확정: 3). 최대 시도 횟수는 4회
    // 크게 잡으면 재시도 폭주가 실패율에 가려져 관측 대상 자체가 사라진다
    private static final int MAX_RETRY = 3;

    private final TransactionTemplate transactionTemplate;
    private final CouponRepository couponRepository;
    private final IssueCore core;
    private final IssueMetrics metrics;

    public W2OptimisticLockStrategy(TransactionTemplate transactionTemplate,
                                    CouponRepository couponRepository,
                                    IssueCore core,
                                    IssueMetrics metrics) {
        this.transactionTemplate = transactionTemplate;
        this.couponRepository = couponRepository;
        this.core = core;
        this.metrics = metrics;
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        for (int retry = 0; ; retry++) {
            IssueResult result = attempt(couponId, userId);
            if (result != IssueResult.CONFLICT || retry == MAX_RETRY) {
                return result;
            }
            // 백오프 없음 — 대기 시간을 넣으면 비교군 간 새 변수가 된다
            metrics.recordRetry();
        }
    }

    // 1회 시도 — 재시도마다 새 트랜잭션에서 버전을 다시 읽어야 한다
    private IssueResult attempt(long couponId, long userId) {
        return transactionTemplate.execute(status ->
                core.issue(couponId, userId, coupon ->
                        couponRepository.increaseIfVersionMatches(coupon.getId(), coupon.getVersion()) == 1));
    }

    @Override
    public String type() {
        return "W2";
    }
}

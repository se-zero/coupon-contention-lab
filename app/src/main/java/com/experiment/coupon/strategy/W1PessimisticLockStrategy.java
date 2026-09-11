package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.repository.CouponRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * W1 — 비관적 락 (SELECT ... FOR UPDATE)
 *
 * 예상 실패 모드: 락 대기 누적 -> 커넥션 풀 고갈 (PROJECT_BRIEF 5.1)
 * W0 와 달리 커넥션을 얻은 뒤에 락을 기다리므로, 대기 중에도 커넥션을 점유한다.
 * 이는 결함이 아니라 관측 대상이므로 고치지 않는다. (작업 규칙 4)
 */
@Component
public class W1PessimisticLockStrategy implements CouponIssueStrategy {

    private final TransactionTemplate transactionTemplate;
    private final CouponRepository couponRepository;
    private final IssueCore core;

    public W1PessimisticLockStrategy(TransactionTemplate transactionTemplate,
                                     CouponRepository couponRepository,
                                     IssueCore core) {
        this.transactionTemplate = transactionTemplate;
        this.couponRepository = couponRepository;
        this.core = core;
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        return transactionTemplate.execute(status -> {
            // 임계 구역 진입 — 락 구간과 트랜잭션 구간이 같다
            couponRepository.findByIdForUpdate(couponId);
            // 잠긴 엔티티가 영속성 컨텍스트에 올라와 있어 본문의 findById 는 DB 를 다시 치지 않는다
            return core.issue(couponId, userId);
        });
    }

    @Override
    public String type() {
        return "W1";
    }
}

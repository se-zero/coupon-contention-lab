package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.IssueResult;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * W3 — 분산 락 (Redisson RLock)
 *
 * W0 와 한 쌍이다 (PROJECT_BRIEF 5.2). 단일 인스턴스에서는 Redis 왕복만큼 느릴 뿐이고
 * 3 인스턴스에서 W0 가 깨질 때 비로소 이 오버헤드가 정당화된다.
 *
 * Redis 장애 시 예외를 잡지 않는다 — Redis 없는 W3 는 상호 배제를 보장하지 못하므로
 * 다른 락으로 대체하면 실험 중간에 전략이 바뀐다. 이는 관측 대상이다. (작업 규칙 4, NFR-05)
 */
@Component
public class W3DistributedLockStrategy implements CouponIssueStrategy {

    // 락 대기 한계 (확정: 3초). 정상 줄서기는 1초대라 고장 났을 때만 걸린다
    private static final long WAIT_SECONDS = 3;

    private final RedissonClient redisson;
    private final TransactionTemplate transactionTemplate;
    private final IssueCore core;

    public W3DistributedLockStrategy(RedissonClient redisson,
                                     TransactionTemplate transactionTemplate,
                                     IssueCore core) {
        this.redisson = redisson;
        this.transactionTemplate = transactionTemplate;
        this.core = core;
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        RLock lock = redisson.getLock("coupon:lock:" + couponId);
        try {
            // leaseTime 없음 — watchdog 이 unlock 까지 자동 연장한다. 커밋 전 만료는 관측 대상이 아니라 버그다
            if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return IssueResult.LOCK_TIMEOUT;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for lock", e);
        }
        try {
            // 커밋까지 락 유지 — 락 해제가 커밋보다 빠르면 경합 창이 열린다
            return transactionTemplate.execute(status -> core.issue(couponId, userId));
        } finally {
            lock.unlock();  // 임계 구역 이탈
        }
    }

    @Override
    public String type() {
        return "W3";
    }
}

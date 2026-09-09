package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.IssueResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.ReentrantLock;

/**
 * W0 — JVM 로컬 락
 *
 * 예상 실패 모드: 인스턴스가 2대 이상이면 정합성 붕괴 (PROJECT_BRIEF 5.2)
 * 각 JVM 이 자기 안에서만 상호 배제하므로 3 인스턴스에서 초과 발급이 발생한다.
 * 이는 결함이 아니라 관측 대상이므로 고치지 않는다. (작업 규칙 4)
 */
@Component
public class W0LocalLockStrategy implements CouponIssueStrategy {

    // JVM 로컬 락 — 프로세스 경계 밖은 보호하지 못한다
    private final ReentrantLock lock = new ReentrantLock();

    private final TransactionTemplate transactionTemplate;
    private final IssueCore core;

    public W0LocalLockStrategy(TransactionTemplate transactionTemplate, IssueCore core) {
        this.transactionTemplate = transactionTemplate;
        this.core = core;
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        lock.lock();  // 임계 구역 진입
        try {
            // 커밋까지 락 유지 — 락 해제가 커밋보다 빠르면 경합 창이 열린다
            return transactionTemplate.execute(status -> core.issue(couponId, userId));
        } finally {
            lock.unlock();  // 임계 구역 이탈
        }
    }

    @Override
    public String type() {
        return "W0";
    }
}

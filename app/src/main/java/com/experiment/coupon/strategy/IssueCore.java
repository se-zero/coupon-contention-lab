package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.Coupon;
import com.experiment.coupon.domain.CouponIssue;
import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.repository.CouponIssueRepository;
import com.experiment.coupon.repository.CouponRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 공통 발급 본문 (조회 -> 검증 -> 차감 -> 저장)
 *
 * 트랜잭션 경계는 여기서 열지 않는다.
 * 각 전략이 TransactionTemplate 로 직접 열고 닫아야 락 구간과 커넥션 홀딩 구간이 명확해진다.
 */
@Component
public class IssueCore {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;

    public IssueCore(CouponRepository couponRepository, CouponIssueRepository issueRepository) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
    }

    // 영속성 컨텍스트 더티 체킹으로 차감 — W0 / W1 / W3 가 쓴다
    private static final StockDecrement DIRTY_CHECKING = coupon -> {
        coupon.increaseIssuedCount();
        return true;
    };

    // 잠금 없는 발급 본문 (호출자가 임계 구역을 보장한다)
    public IssueResult issue(long couponId, long userId) {
        return issue(couponId, userId, DIRTY_CHECKING);
    }

    // 차감 방식을 지정하는 발급 본문 — 나머지 단계는 전 전략 동일
    public IssueResult issue(long couponId, long userId, StockDecrement decrement) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            return IssueResult.COUPON_NOT_FOUND;
        }
        if (IssueValidator.outOfPeriod(coupon, LocalDateTime.now())) {
            return IssueResult.NOT_IN_PERIOD;
        }
        if (IssueValidator.soldOut(coupon)) {
            return IssueResult.SOLD_OUT;
        }
        if (issueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            return IssueResult.DUPLICATE;
        }

        // 차감 실패 = 읽은 뒤 남이 먼저 바꿨다는 뜻
        if (!decrement.apply(coupon)) {
            return IssueResult.CONFLICT;
        }

        // flush 강제 — UNIQUE 위반을 트랜잭션 경계 안에서 드러낸다
        issueRepository.saveAndFlush(new CouponIssue(couponId, userId));
        return IssueResult.ISSUED;
    }
}

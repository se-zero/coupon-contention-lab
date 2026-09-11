package com.experiment.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 쿠폰 (재고 경합의 대상)
 *
 * 주의: @Version 필드를 두지 않는다.
 * JPA 낙관적 락이 전 전략에 암묵 적용되면 W0 의 다중 인스턴스 붕괴가 가려진다. (PROJECT_BRIEF 5.2)
 * version 컬럼은 두되 @Version 없이 평범한 컬럼으로 매핑한다. W2 만 조건부 UPDATE 로 이 값을 쓴다.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    // W2 낙관적 락용 — @Version 이 아니라 평범한 컬럼이다
    @Column(nullable = false)
    private long version;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    protected Coupon() {
    }

    // 재고 차감 (발급 수량 증가)
    public void increaseIssuedCount() {
        this.issuedCount++;
    }

    // 잔여 수량
    public int remaining() {
        return totalQuantity - issuedCount;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }
}

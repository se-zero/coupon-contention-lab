package com.experiment.coupon.repository;

import com.experiment.coupon.domain.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // 비관적 락 — W1 전용 (PESSIMISTIC_WRITE -> for no key update)
    // 커밋까지 행이 잠긴다. 해제 명령은 없다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") long id);

    // 낙관적 락 — W2 전용. 읽은 버전이 그대로일 때만 차감한다
    // 영향 행 0 = 읽은 뒤 남이 먼저 바꿨다는 뜻
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Coupon c
               set c.issuedCount = c.issuedCount + 1,
                   c.version = c.version + 1
             where c.id = :id
               and c.version = :version
            """)
    int increaseIfVersionMatches(@Param("id") long id, @Param("version") long version);
}

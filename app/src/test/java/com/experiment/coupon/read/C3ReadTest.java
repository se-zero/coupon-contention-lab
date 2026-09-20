package com.experiment.coupon.read;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 — 뮤텍스 / 단일 재계산 (D-13)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.cache=C3")
class C3ReadTest extends AbstractCouponReadTest {

    private static final int THREADS = 16;

    @Autowired
    RedissonClient redisson;

    @Autowired
    MeterRegistry meterRegistry;

    // 콜드 캐시에서 출발해야 "동시 미스" 가 재현된다 (W4ConcurrencyTest 와 같은 이유)
    @BeforeEach
    void resetRedis() {
        redisson.getKeys().flushall();
    }

    @Override
    protected String expectedStrategy() {
        return "C3";
    }

    @Test
    @DisplayName("같은 키에 동시 미스 N 개 — DB 조회는 1번뿐이고 로더를 뺀 미스는 전부 wait 에 기록된다")
    void 동시_미스에서_DB_조회는_한번뿐이다() throws InterruptedException {
        double dbLoadBefore = dbLoadCount();
        double hitBefore = hitCount();
        double missBefore = missCount();
        long waitCountBefore = waitCount();

        runConcurrently(THREADS);

        double dbLoadDelta = dbLoadCount() - dbLoadBefore;
        double hitDelta = hitCount() - hitBefore;
        double missDelta = missCount() - missBefore;
        long waitDelta = waitCount() - waitCountBefore;

        // 타이밍상 일부 스레드가 로더가 채운 뒤 도착해 (재확인이 아니라 최초 조회부터) 히트가 될 수 있다
        assertThat(dbLoadDelta).isEqualTo(1.0);
        assertThat(hitDelta + missDelta).isEqualTo((double) THREADS);
        // 미스 중 로더 하나를 뺀 나머지는 전부 더블 체크에서 값을 만나 wait 에 기록된다
        assertThat(waitDelta).isEqualTo((long) missDelta - 1);
    }

    // 지정한 수의 요청을 동시에 발사한다 (동시 출발 보장) — AbstractIssueConcurrencyTest 와 같은 방식
    private void runConcurrently(int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    readService.findById(COUPON_ID);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    private double hitCount() {
        return meterRegistry.get("coupon.cache").tag("result", "hit").counter().count();
    }

    private double missCount() {
        return meterRegistry.get("coupon.cache").tag("result", "miss").counter().count();
    }

    private double dbLoadCount() {
        return meterRegistry.get("coupon.cache.db_load").counter().count();
    }

    private long waitCount() {
        return meterRegistry.get("coupon.cache.wait").timer().count();
    }
}

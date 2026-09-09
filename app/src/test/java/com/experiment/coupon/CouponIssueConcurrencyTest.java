package com.experiment.coupon;

import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.service.CouponIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-01 / NFR-02 검증 (단일 인스턴스)
 *
 * 구현을 그대로 따라 쓰지 않고 요구사항 자체를 검증한다. (PROJECT_BRIEF 작업 규칙 8)
 * 다중 인스턴스에서 W0 가 깨지는 것은 2단계 실험에서 확인하며, 여기서는 다루지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CouponIssueConcurrencyTest {

    private static final long COUPON_ID = 1L;
    private static final int STOCK = 100;
    private static final int CONCURRENT_REQUESTS = 1000;
    private static final int THREADS = 64;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            // init.sql 의 pg_stat_statements 확장 생성을 위해 운영과 동일한 옵션을 준다
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
            .withInitScript("init.sql");

    @Autowired
    CouponIssueService issueService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE coupon_issue, coupon RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO coupon (id, name, total_quantity, issued_count, start_at, end_at)
                VALUES (?, 'test-coupon', ?, 0, now() - interval '1 hour', now() + interval '1 day')
                """, COUPON_ID, STOCK);
    }

    @Test
    @DisplayName("NFR-01 — 재고보다 많은 요청이 동시에 들어와도 초과 발급이 발생하지 않는다")
    void 초과_발급이_없다() throws InterruptedException {
        AtomicInteger issued = new AtomicInteger();

        // user_id 를 요청마다 다르게 준다 — UNIQUE 제약이 아니라 재고 로직이 막는지를 본다
        runConcurrently(CONCURRENT_REQUESTS, i -> {
            if (issueService.issue(COUPON_ID, i) == IssueResult.ISSUED) {
                issued.incrementAndGet();
            }
        });

        int actualRows = countIssues();
        int counterValue = counterValue();

        assertThat(issued.get()).isEqualTo(STOCK);
        assertThat(actualRows).isEqualTo(STOCK);       // 실제 발급 행 수
        assertThat(counterValue).isEqualTo(STOCK);     // issued_count 와 일치 (NFR-01)
    }

    @Test
    @DisplayName("NFR-02 — 같은 유저가 동시에 여러 번 요청해도 1매만 발급된다")
    void 중복_발급이_없다() throws InterruptedException {
        long sameUserId = 777L;
        AtomicInteger issued = new AtomicInteger();

        runConcurrently(CONCURRENT_REQUESTS, i -> {
            if (issueService.issue(COUPON_ID, sameUserId) == IssueResult.ISSUED) {
                issued.incrementAndGet();
            }
        });

        assertThat(issued.get()).isEqualTo(1);
        assertThat(countIssues()).isEqualTo(1);
        assertThat(duplicateUserCount()).isZero();
    }

    // 지정한 수의 요청을 동시에 발사한다 (동시 출발 보장)
    private void runConcurrently(int requests, java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            int userId = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(3, TimeUnit.MINUTES)).isTrue();
        pool.shutdown();
    }

    private int countIssues() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM coupon_issue WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }

    private int counterValue() {
        return jdbc.queryForObject(
                "SELECT issued_count FROM coupon WHERE id = ?", Integer.class, COUPON_ID);
    }

    private int duplicateUserCount() {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(cnt - 1), 0) FROM (
                    SELECT count(*) AS cnt FROM coupon_issue
                    WHERE coupon_id = ? GROUP BY user_id HAVING count(*) > 1) d
                """, Integer.class, COUPON_ID);
    }
}

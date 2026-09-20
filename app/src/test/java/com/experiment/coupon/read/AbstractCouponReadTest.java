package com.experiment.coupon.read;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.service.CouponReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 응답 검증 본문 (단일 인스턴스)
 *
 * 전략마다 하위 클래스를 하나씩 두고 coupon.cache 만 바꾼다.
 * 비교군 간 차이는 오직 캐시 전략뿐이어야 하므로 검증 기준도 전 전략 공통이다. (PROJECT_BRIEF 5.4)
 *
 * 구현을 그대로 따라 쓰지 않고 요구사항 자체를 검증한다. (작업 규칙 8)
 * TTL 안에서 stale 이 나오는 것은 실패가 아니라 명세다 (docs/cache-basics.md 3-3) — 하위 클래스가 이를 구분해 검증한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractCouponReadTest {

    protected static final long COUPON_ID = 1L;
    private static final long MISSING_COUPON_ID = 999L;
    protected static final LocalDateTime START_AT = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
    protected static final LocalDateTime END_AT = LocalDateTime.of(2030, 1, 1, 0, 0, 0);

    // 전 전략이 컨테이너 하나를 공유한다 — 전략마다 띄우면 기동 시간이 전략 수만큼 늘어난다
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
            .withInitScript("init.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    protected CouponReadService readService;

    @Autowired
    protected JdbcTemplate jdbc;

    // 하위 클래스가 선언한 전략 식별자 ("C0" ~ "C4")
    protected abstract String expectedStrategy();

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE coupon_issue, coupon RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO coupon (id, name, total_quantity, issued_count, start_at, end_at)
                VALUES (?, 'test-coupon', 100, 30, ?, ?)
                """, COUPON_ID, START_AT, END_AT);
    }

    @Test
    @DisplayName("요청한 전략이 실제로 적용되었다")
    void 적용된_전략이_일치한다() {
        // 설정 오타로 엉뚱한 전략을 검증하는 사고 방지 — W 테스트들과 같은 취지
        assertThat(readService.strategyType()).isEqualTo(expectedStrategy());
    }

    @Test
    @DisplayName("존재하는 쿠폰 조회 시 응답 필드가 DB 값과 정확히 일치한다")
    void 응답이_DB_값과_일치한다() {
        Optional<CouponResponse> result = readService.findById(COUPON_ID);

        assertThat(result).isPresent();
        CouponResponse response = result.get();
        assertThat(response.id()).isEqualTo(COUPON_ID);
        assertThat(response.name()).isEqualTo("test-coupon");
        assertThat(response.totalQuantity()).isEqualTo(100);
        assertThat(response.issuedCount()).isEqualTo(30);
        assertThat(response.remaining()).isEqualTo(70);
        assertThat(response.startAt()).isEqualTo(START_AT);
        assertThat(response.endAt()).isEqualTo(END_AT);
    }

    @Test
    @DisplayName("같은 쿠폰을 두 번 조회하면 같은 응답이다")
    void 두번_조회해도_같은_응답이다() {
        CouponResponse first = readService.findById(COUPON_ID).orElseThrow();
        CouponResponse second = readService.findById(COUPON_ID).orElseThrow();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("없는 쿠폰은 빈 결과다")
    void 없는_쿠폰은_빈_결과다() {
        assertThat(readService.findById(MISSING_COUPON_ID)).isEmpty();
    }
}

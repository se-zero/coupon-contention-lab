package com.experiment.coupon.read;

import com.experiment.coupon.api.dto.CouponResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 — Cache-Aside + 고정 TTL
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.cache=C1")
class C1ReadTest extends AbstractCouponReadTest {

    private static final String CACHE_KEY = "coupon:detail:" + COUPON_ID;

    @Autowired
    RedissonClient redisson;

    @Autowired
    MeterRegistry meterRegistry;

    @Override
    protected String expectedStrategy() {
        return "C1";
    }

    @Test
    @DisplayName("첫 조회 뒤 Redis 에 캐시 키가 생기고 TTL 이 0 보다 크고 60 이하다")
    void 첫_조회_뒤_캐시_키가_생긴다() {
        readService.findById(COUPON_ID);

        RBucket<String> bucket = redisson.getBucket(CACHE_KEY, StringCodec.INSTANCE);
        assertThat(bucket.isExists()).isTrue();
        long ttlMillis = bucket.remainTimeToLive();
        assertThat(ttlMillis).isGreaterThan(0).isLessThanOrEqualTo(60_000L);
    }

    @Test
    @DisplayName("두 번째 조회는 db_load 카운터를 올리지 않는다 — 캐시가 실제로 DB 조회를 막았다")
    void 두번째_조회는_db_load_를_올리지_않는다() {
        readService.findById(COUPON_ID); // 미스 — db_load +1
        double afterFirst = dbLoadCount();

        readService.findById(COUPON_ID); // 히트 — db_load 그대로
        double afterSecond = dbLoadCount();

        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("TTL 안에 DB 값이 바뀌어도 캐시는 옛 값을 반환한다 — 실패가 아니라 명세다 (docs/cache-basics.md 3-3)")
    void TTL_안에서는_DB_변경이_반영되지_않는다() {
        CouponResponse cached = readService.findById(COUPON_ID).orElseThrow(); // 캐시 채움

        jdbc.update("UPDATE coupon SET issued_count = ? WHERE id = ?", 99, COUPON_ID);

        CouponResponse afterDbChange = readService.findById(COUPON_ID).orElseThrow();

        assertThat(afterDbChange).isEqualTo(cached);
    }

    private double dbLoadCount() {
        return meterRegistry.get("coupon.cache.db_load").counter().count();
    }
}

package com.experiment.coupon.read;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.strategy.C4XFetchStrategy.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 — 확률적 조기 갱신 / XFetch (D-14)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.cache=C4")
class C4ReadTest extends AbstractCouponReadTest {

    private static final String CACHE_KEY = "coupon:detail:" + COUPON_ID;

    @Autowired
    RedissonClient redisson;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ObjectMapper objectMapper;

    // 다른 전략 테스트가 같은 키에 남긴 캐시가 섞이지 않게 한다 (W4ConcurrencyTest 와 같은 이유)
    @BeforeEach
    void resetRedis() {
        redisson.getKeys().flushall();
    }

    @Override
    protected String expectedStrategy() {
        return "C4";
    }

    @Test
    @DisplayName("첫 조회 뒤 저장된 값에 delta 와 만료 시각이 있다")
    void 첫_조회_뒤_delta와_만료시각이_저장된다() throws Exception {
        long before = System.currentTimeMillis();

        readService.findById(COUPON_ID);

        RBucket<String> bucket = redisson.getBucket(CACHE_KEY, StringCodec.INSTANCE);
        String json = bucket.get();
        assertThat(json).isNotNull();

        Envelope envelope = objectMapper.readValue(json, Envelope.class);
        assertThat(envelope.deltaSeconds()).isGreaterThanOrEqualTo(0.0);
        assertThat(envelope.expiresAtMillis()).isGreaterThan(before);
    }

    @Test
    @DisplayName("만료 시각이 이미 지난 값을 읽으면 조기 갱신이 일어난다 — hit + db_load, miss 는 그대로")
    void 만료_시각이_지나면_조기_갱신이_일어난다() throws Exception {
        // 실제 DB 값과 다르게 만들어 반환값이 캐시된 stale 값이 아니라 새로 읽은 DB 값인지 구분한다
        CouponResponse stale = new CouponResponse(COUPON_ID, "stale-name", 100, 999, 1, START_AT, END_AT);
        Envelope expired = new Envelope(stale, 0.01, System.currentTimeMillis() - 1_000);
        RBucket<String> bucket = redisson.getBucket(CACHE_KEY, StringCodec.INSTANCE);
        bucket.set(objectMapper.writeValueAsString(expired), Duration.ofSeconds(60));

        double dbLoadBefore = dbLoadCount();
        double hitBefore = hitCount();
        double missBefore = missCount();

        CouponResponse result = readService.findById(COUPON_ID).orElseThrow();

        assertThat(dbLoadCount()).isEqualTo(dbLoadBefore + 1);
        assertThat(hitCount()).isEqualTo(hitBefore + 1);
        assertThat(missCount()).isEqualTo(missBefore);
        assertThat(result.issuedCount()).isEqualTo(30);
        assertThat(result.name()).isEqualTo("test-coupon");
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
}

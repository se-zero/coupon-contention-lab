package com.experiment.coupon.read;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 — TTL 지터 (D-14)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.cache=C2")
class C2ReadTest extends AbstractCouponReadTest {

    private static final String CACHE_KEY = "coupon:detail:" + COUPON_ID;

    @Autowired
    RedissonClient redisson;

    // 다른 전략 테스트가 같은 키에 남긴 캐시가 섞이면 TTL 검증이 leftover 값을 보게 된다 (W4ConcurrencyTest 와 같은 이유)
    @BeforeEach
    void resetRedis() {
        redisson.getKeys().flushall();
    }

    @Override
    protected String expectedStrategy() {
        return "C2";
    }

    @Test
    @DisplayName("첫 조회 뒤 Redis 키의 TTL 이 지터 폭(50~70초) 안에 있다")
    void 첫_조회_뒤_TTL_이_지터_폭_안에_있다() {
        readService.findById(COUPON_ID);

        RBucket<String> bucket = redisson.getBucket(CACHE_KEY, StringCodec.INSTANCE);
        long ttlMillis = bucket.remainTimeToLive();
        // 측정 시점이 set() 직후라 실제 경과는 수 ms 뿐이지만, 하한에 여유를 둬 그 경과로 인한 flake 를 막는다
        assertThat(ttlMillis).isGreaterThan(49_000L).isLessThanOrEqualTo(70_000L);
    }
}

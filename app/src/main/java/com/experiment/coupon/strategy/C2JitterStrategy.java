package com.experiment.coupon.strategy;

import com.experiment.coupon.metrics.CacheMetrics;
import com.experiment.coupon.repository.CouponRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * C2 — TTL 지터 (D-14)
 *
 * C1 과 다른 곳은 TTL 계산 한 줄뿐이다 — {@link #ttl()} 만 override 한다.
 * 캐시 조회·DB 읽기·직렬화는 C1 을 상속해 그대로 쓴다.
 */
@Component
public class C2JitterStrategy extends C1CacheAsideStrategy {

    private final long jitterSeconds;

    public C2JitterStrategy(RedissonClient redisson,
                            CouponRepository couponRepository,
                            ObjectMapper objectMapper,
                            CacheMetrics metrics,
                            @Value("${coupon.cache-ttl-seconds}") long ttlSeconds,
                            @Value("${coupon.cache-jitter-seconds}") long jitterSeconds) {
        super(redisson, couponRepository, objectMapper, metrics, ttlSeconds);
        this.jitterSeconds = jitterSeconds;
    }

    // TTL = 기본 ± J 초, 넣을 때마다 새로 뽑는다 (docs/cache-stampede-defense.md 3-1)
    @Override
    protected Duration ttl() {
        long offset = ThreadLocalRandom.current().nextLong(-jitterSeconds, jitterSeconds + 1);
        return Duration.ofSeconds(ttlSeconds + offset);
    }

    @Override
    public String type() {
        return "C2";
    }
}

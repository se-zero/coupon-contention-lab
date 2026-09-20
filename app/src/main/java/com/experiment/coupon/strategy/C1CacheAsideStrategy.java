package com.experiment.coupon.strategy;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.metrics.CacheMetrics;
import com.experiment.coupon.repository.CouponRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * C1 — Cache-Aside + 고정 TTL (스탬피드 재현용)
 *
 * 캐시 클라이언트는 Redisson RBucket 이다 (D-11) — Lettuce/Spring Data Redis/@Cacheable 은 쓰지 않는다.
 * RBucket<String> + StringCodec 로 JSON 문자열을 직접 넣는다.
 * Redisson 기본 코덱은 LocalDateTime 이 든 record 에 별도 설정이 필요하고,
 * 문자열로 두면 redis-cli GET 으로 값을 눈으로 확인할 수 있다.
 * 직렬화는 스프링이 만든 ObjectMapper 를 그대로 쓴다 (JavaTimeModule 이 이미 등록돼 있다).
 *
 * DB 조회는 히트 경로에서 아예 일어나지 않으므로 메서드 전체를 트랜잭션으로 감싸지 않는다.
 * 감싸면 히트에서도 커넥션을 미리 잡아 "히트 = 0 커넥션"(docs/cache-basics.md 3-4)이 깨진다.
 * CouponRepository.findById 는 Spring Data 가 자체적으로 트랜잭션을 연다.
 */
@Component
public class C1CacheAsideStrategy implements CouponReadStrategy {

    private static final String KEY_PREFIX = "coupon:detail:";

    private final RedissonClient redisson;
    private final CouponRepository couponRepository;
    private final ObjectMapper objectMapper;
    private final CacheMetrics metrics;
    private final long ttlSeconds;

    public C1CacheAsideStrategy(RedissonClient redisson,
                                CouponRepository couponRepository,
                                ObjectMapper objectMapper,
                                CacheMetrics metrics,
                                @Value("${coupon.cache-ttl-seconds}") long ttlSeconds) {
        this.redisson = redisson;
        this.couponRepository = couponRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<CouponResponse> findById(long couponId) {
        RBucket<String> bucket = redisson.getBucket(key(couponId), StringCodec.INSTANCE);
        String cached = bucket.get();
        if (cached != null) {
            metrics.recordHit();
            return Optional.of(deserialize(cached));
        }

        metrics.recordMiss();
        long start = System.nanoTime();
        Optional<CouponResponse> result = couponRepository.findById(couponId).map(CouponResponse::from);
        metrics.recordDbLoad(Duration.ofNanos(System.nanoTime() - start));

        // 없는 쿠폰은 캐시하지 않는다 — 캐시 관통 방어는 이 실험 범위 밖이고 부하는 실재하는 키만 조회한다 (D-12)
        result.ifPresent(response -> bucket.set(serialize(response), Duration.ofSeconds(ttlSeconds)));
        return result;
    }

    private String serialize(CouponResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize coupon response", e);
        }
    }

    private CouponResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, CouponResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize coupon response", e);
        }
    }

    private static String key(long couponId) {
        return KEY_PREFIX + couponId;
    }

    @Override
    public String type() {
        return "C1";
    }
}

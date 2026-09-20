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
import java.util.concurrent.ThreadLocalRandom;

/**
 * C4 — 확률적 조기 갱신 / XFetch (D-14)
 *
 * 저장 형식이 C1 과 다르다 — 값 + Δ(직전 갱신 소요 시간) + 만료 시각을 한 JSON 에 담는다.
 * Redis 키 TTL 은 60초 그대로 두고(실제 만료는 Redis 가 한다), 조기 갱신 판정은 값 안의 만료 시각으로 한다.
 * 캐시 조회·DB 읽기는 C1 을 상속해 그대로 쓴다.
 */
@Component
public class C4XFetchStrategy extends C1CacheAsideStrategy {

    private final double beta;

    public C4XFetchStrategy(RedissonClient redisson,
                            CouponRepository couponRepository,
                            ObjectMapper objectMapper,
                            CacheMetrics metrics,
                            @Value("${coupon.cache-ttl-seconds}") long ttlSeconds,
                            @Value("${coupon.cache-xfetch-beta}") double beta) {
        super(redisson, couponRepository, objectMapper, metrics, ttlSeconds);
        this.beta = beta;
    }

    @Override
    public Optional<CouponResponse> findById(long couponId) {
        RBucket<String> bucket = redisson.getBucket(key(couponId), StringCodec.INSTANCE);
        String json = bucket.get();

        if (json == null) {
            // 진짜 미스 — 캐시에 값 자체가 없었다
            metrics.recordMiss();
            return loadAndSave(bucket, couponId);
        }

        Envelope env = deserializeEnvelope(json);
        // 캐시에 값이 있었다 — 조기 갱신이 일어나도 미스가 아니다 (C3 와 갈리는 지점)
        metrics.recordHit();

        long remainingMs = env.expiresAtMillis() - System.currentTimeMillis();
        // rand ∈ [0,1). ln(rand) 를 쓰면 rand=0 에서 -Infinity 가 나와 매번 갱신된다.
        // ln(1-rand) 로 쓰면 1-rand ∈ (0,1] 이라 이 문제가 없다
        double rand = ThreadLocalRandom.current().nextDouble();
        double thresholdMs = -beta * env.deltaSeconds() * 1000 * Math.log(1 - rand);

        if (remainingMs < thresholdMs) {
            // 조기 갱신 — 이 요청이 직접 갱신한다. 같은 판정 창에 도착한 다른 요청도 각자 갱신할 수 있다
            // (동시 조기 갱신은 막지 않는다 — 확률적 알고리즘의 본성)
            return loadAndSave(bucket, couponId);
        }
        return Optional.of(env.value());
    }

    // 진짜 미스 · 조기 갱신 공통 경로 — DB 를 읽고 delta 를 실측해 Envelope 으로 다시 저장한다
    private Optional<CouponResponse> loadAndSave(RBucket<String> bucket, long couponId) {
        long start = System.nanoTime();
        Optional<CouponResponse> result = queryDb(couponId);
        long elapsedNanos = System.nanoTime() - start;
        double deltaSeconds = elapsedNanos / 1_000_000_000.0;
        result.ifPresent(value -> bucket.set(
                serializeEnvelope(new Envelope(value, deltaSeconds, System.currentTimeMillis() + ttlSeconds * 1000)),
                ttl()));
        metrics.recordDbLoad(Duration.ofNanos(elapsedNanos));
        return result;
    }

    private String serializeEnvelope(Envelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cache envelope", e);
        }
    }

    private Envelope deserializeEnvelope(String json) {
        try {
            return objectMapper.readValue(json, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cache envelope", e);
        }
    }

    @Override
    public String type() {
        return "C4";
    }

    // 캐시 저장 형식 — 값 + Δ(직전 갱신 소요 시간, 초) + 만료 시각(epoch millis)
    public record Envelope(CouponResponse value, double deltaSeconds, long expiresAtMillis) {
    }
}

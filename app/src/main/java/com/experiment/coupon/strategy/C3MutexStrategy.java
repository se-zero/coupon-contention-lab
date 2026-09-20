package com.experiment.coupon.strategy;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.metrics.CacheMetrics;
import com.experiment.coupon.repository.CouponRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * C3 — 뮤텍스 / 단일 재계산 (D-13)
 *
 * 미스를 만난 요청 중 하나(로더)만 DB 를 읽고, 나머지(대기자)는 락 대기 뒤 캐시를 다시 본다 (더블 체크).
 * 대기 상한(기본 2초)을 넘기면 대기를 포기하고 직접 DB 를 읽는다 — 요청을 버리지 않는다.
 * 캐시 조회·DB 읽기·직렬화는 C1 을 상속해 그대로 쓴다.
 */
@Component
public class C3MutexStrategy extends C1CacheAsideStrategy {

    private final long mutexWaitMs;

    // 키별 락. 지우지 않는다 — 키 20개라 문제없다. 실무에서는 키가 늘어나는 만큼 정리가 필요하다
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public C3MutexStrategy(RedissonClient redisson,
                           CouponRepository couponRepository,
                           ObjectMapper objectMapper,
                           CacheMetrics metrics,
                           @Value("${coupon.cache-ttl-seconds}") long ttlSeconds,
                           @Value("${coupon.cache-mutex-wait-ms}") long mutexWaitMs) {
        super(redisson, couponRepository, objectMapper, metrics, ttlSeconds);
        this.mutexWaitMs = mutexWaitMs;
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
        ReentrantLock lock = locks.computeIfAbsent(couponId, id -> new ReentrantLock());
        long waitStart = System.nanoTime();
        boolean acquired;
        try {
            acquired = lock.tryLock(mutexWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for cache mutex", e);
        }

        if (!acquired) {
            // 상한 도달 — 기다리기를 포기하고 이 요청이 직접 DB 를 읽는다
            metrics.recordWaitTimeout();
            return loadAndFill(bucket, couponId);
        }

        try {
            String recheck = bucket.get();
            if (recheck != null) {
                // 대기자 — 기다리는 동안 로더가 채웠다.
                // wait 타이머는 여기(더블 체크에서 값을 만난 경우)만 기록한다 — 로더·상한 도달자를 섞으면
                // wait 분포에 상한(2초) 값이 끼어들어 "성공적으로 기다린 시간"이 아니게 된다
                metrics.recordWait(Duration.ofNanos(System.nanoTime() - waitStart));
                return Optional.of(deserialize(recheck));
            }
            // 로더 — 이 요청만 DB 를 읽는다
            return loadAndFill(bucket, couponId);
        } finally {
            lock.unlock();
        }
    }

    private Optional<CouponResponse> loadAndFill(RBucket<String> bucket, long couponId) {
        Optional<CouponResponse> result = loadFromDb(couponId);
        result.ifPresent(response -> bucket.set(serialize(response), ttl()));
        return result;
    }

    @Override
    public String type() {
        return "C3";
    }
}

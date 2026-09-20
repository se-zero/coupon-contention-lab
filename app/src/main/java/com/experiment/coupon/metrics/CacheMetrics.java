package com.experiment.coupon.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 조회 캐시 커스텀 메트릭
 *
 * 모든 카운터·타이머를 기동 시점에 미리 등록한다 (IssueMetrics 와 같은 이유).
 * wait / wait.timeout 은 지금 등록만 하고 아무도 올리지 않는다 — 9단계 C3 전용이다.
 * 미리 등록해 두어야 값이 한 번도 안 생겨도 Grafana 패널이 비지 않고, 9단계에 이 클래스를 다시 안 건드린다.
 */
@Component
public class CacheMetrics {

    private static final String CACHE_COUNTER = "coupon.cache";
    private static final String DB_LOAD_COUNTER = "coupon.cache.db_load";
    private static final String LOAD_TIMER = "coupon.cache.load";
    private static final String WAIT_TIMER = "coupon.cache.wait";
    private static final String WAIT_TIMEOUT_COUNTER = "coupon.cache.wait.timeout";

    private final MeterRegistry registry;
    private Counter hitCounter;
    private Counter missCounter;
    private Counter dbLoadCounter;
    private Timer loadTimer;
    private Timer waitTimer;
    private Counter waitTimeoutCounter;

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        hitCounter = Counter.builder(CACHE_COUNTER)
                .tag("result", "hit")
                .description("캐시 히트·미스 건수")
                .register(registry);
        missCounter = Counter.builder(CACHE_COUNTER)
                .tag("result", "miss")
                .description("캐시 히트·미스 건수")
                .register(registry);
        // 미스로 DB 를 실제로 읽은 횟수 — 스크레이프 1초라 초당 증가분이 그대로 DB QPS 다 (docs/cache-basics.md 4장)
        dbLoadCounter = Counter.builder(DB_LOAD_COUNTER)
                .description("미스로 DB 를 실제로 읽은 횟수")
                .register(registry);
        // 두 타이머는 percentile histogram 을 켠다 — 전체 P99 로는 C3 의 대가가 안 보인다 (docs/cache-stampede.md 7장)
        loadTimer = Timer.builder(LOAD_TIMER)
                .description("미스 후 DB 를 읽은 요청의 소요 시간")
                .publishPercentileHistogram()
                .register(registry);
        // 미스 후 남이 채우기를 기다린 시간 — C3 전용 (9단계)
        waitTimer = Timer.builder(WAIT_TIMER)
                .description("미스 후 남이 채우기를 기다린 시간 (C3 전용)")
                .publishPercentileHistogram()
                .register(registry);
        // 대기 상한(2초) 도달 건수 — C3 전용 (9단계, D-13)
        waitTimeoutCounter = Counter.builder(WAIT_TIMEOUT_COUNTER)
                .description("대기 상한(2초) 도달 건수")
                .register(registry);
    }

    // 히트 집계
    public void recordHit() {
        hitCounter.increment();
    }

    // 미스 집계
    public void recordMiss() {
        missCounter.increment();
    }

    // DB 조회 집계 + 소요 시간
    public void recordDbLoad(Duration elapsed) {
        dbLoadCounter.increment();
        loadTimer.record(elapsed);
    }

    // 대기 시간 집계 (C3 전용, 9단계가 호출)
    public void recordWait(Duration elapsed) {
        waitTimer.record(elapsed);
    }

    // 대기 상한 도달 집계 (C3 전용, 9단계가 호출)
    public void recordWaitTimeout() {
        waitTimeoutCounter.increment();
    }
}

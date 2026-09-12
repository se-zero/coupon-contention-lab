package com.experiment.coupon.metrics;

import com.experiment.coupon.domain.IssueResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 발급 커스텀 메트릭
 *
 * 모든 카운터를 기동 시점에 0 으로 미리 등록한다.
 * 값이 한 번도 발생하지 않으면 Prometheus 에 시계열이 생기지 않아 Grafana 패널이 비어 보인다.
 */
@Component
public class IssueMetrics {

    private static final String ISSUE_COUNTER = "coupon.issue";
    private static final String VIOLATION_COUNTER = "coupon.issue.constraint.violation";
    private static final String RETRY_COUNTER = "coupon.issue.retry";
    private static final String REFLECTED_COUNTER = "coupon.w4.reflected";
    private static final String QUEUE_SIZE_GAUGE = "coupon.w4.queue.size";

    private final MeterRegistry registry;
    private final Map<IssueResult, Counter> resultCounters = new EnumMap<>(IssueResult.class);
    private Counter constraintViolationCounter;
    private Counter retryCounter;
    private Counter reflectedCounter;
    private final AtomicLong queueSize = new AtomicLong();

    public IssueMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        for (IssueResult result : IssueResult.values()) {
            resultCounters.put(result, Counter.builder(ISSUE_COUNTER)
                    .tag("result", result.name())
                    .description("발급 결과별 건수")
                    .register(registry));
        }
        // UNIQUE 제약 위반 — 전략의 중복 방어가 실패했다는 신호 (NFR-02)
        constraintViolationCounter = Counter.builder(VIOLATION_COUNTER)
                .description("UNIQUE 제약에 걸린 건수 (전략 방어 실패)")
                .register(registry);
        // 낙관적 충돌로 다시 시도한 횟수 (W2)
        retryCounter = Counter.builder(RETRY_COUNTER)
                .description("낙관적 충돌 재시도 횟수")
                .register(registry);
        // 비동기 DB 반영 (W4) — 큐 길이의 시간 곡선이 반영 지연이다
        reflectedCounter = Counter.builder(REFLECTED_COUNTER)
                .description("워커가 DB 에 반영한 건수")
                .register(registry);
        Gauge.builder(QUEUE_SIZE_GAUGE, queueSize, AtomicLong::get)
                .description("DB 반영 대기 중인 발급 건수")
                .register(registry);
    }

    // 결과별 집계
    public void recordResult(IssueResult result) {
        resultCounters.get(result).increment();
    }

    // 제약 위반 집계
    public void recordConstraintViolation() {
        constraintViolationCounter.increment();
    }

    // 재시도 집계
    public void recordRetry() {
        retryCounter.increment();
    }

    // 반영 건수 집계
    public void recordReflected(int count) {
        reflectedCounter.increment(count);
    }

    // 반영 대기 큐 길이
    public void recordQueueSize(long size) {
        queueSize.set(size);
    }
}

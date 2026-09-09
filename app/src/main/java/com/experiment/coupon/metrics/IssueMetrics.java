package com.experiment.coupon.metrics;

import com.experiment.coupon.domain.IssueResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

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

    private final MeterRegistry registry;
    private final Map<IssueResult, Counter> resultCounters = new EnumMap<>(IssueResult.class);
    private Counter constraintViolationCounter;

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
    }

    // 결과별 집계
    public void recordResult(IssueResult result) {
        resultCounters.get(result).increment();
    }

    // 제약 위반 집계
    public void recordConstraintViolation() {
        constraintViolationCounter.increment();
    }
}

package com.experiment.coupon.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    /**
     * 공통 태그
     *
     * strategy — 비교군 구분 (실험 A)
     * app_instance — 2단계에서 어느 인스턴스가 처리했는지 구분 (PROJECT_BRIEF 7장)
     *
     * 라벨명이 instance 이면 Prometheus 가 스크레이프 대상 주소로 덮어쓰고
     * 앱이 붙인 값은 exported_instance 로 밀려난다. 그래서 이름을 분리한다.
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${coupon.strategy}") String strategy,
            @Value("${coupon.instance-id}") String instanceId) {
        return registry -> registry.config().commonTags(
                "strategy", strategy,
                "app_instance", instanceId);
    }
}

package com.experiment.coupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 스케줄링은 W4 반영 워커용
@SpringBootApplication
@EnableScheduling
public class CouponExperimentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouponExperimentApplication.class, args);
    }
}

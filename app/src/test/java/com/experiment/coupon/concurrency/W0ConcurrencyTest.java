package com.experiment.coupon.concurrency;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * W0 — JVM 로컬 락
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.strategy=W0")
class W0ConcurrencyTest extends AbstractIssueConcurrencyTest {

    @Override
    protected String expectedStrategy() {
        return "W0";
    }
}

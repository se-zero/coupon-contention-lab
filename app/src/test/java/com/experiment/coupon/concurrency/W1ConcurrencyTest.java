package com.experiment.coupon.concurrency;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * W1 — 비관적 락
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.strategy=W1")
class W1ConcurrencyTest extends AbstractIssueConcurrencyTest {

    @Override
    protected String expectedStrategy() {
        return "W1";
    }
}

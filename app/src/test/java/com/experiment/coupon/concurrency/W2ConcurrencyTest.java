package com.experiment.coupon.concurrency;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * W2 — 낙관적 락
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.strategy=W2")
class W2ConcurrencyTest extends AbstractIssueConcurrencyTest {

    @Override
    protected String expectedStrategy() {
        return "W2";
    }
}

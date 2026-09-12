package com.experiment.coupon.concurrency;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * W3 — 분산 락
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.strategy=W3")
class W3ConcurrencyTest extends AbstractIssueConcurrencyTest {

    @Override
    protected String expectedStrategy() {
        return "W3";
    }
}

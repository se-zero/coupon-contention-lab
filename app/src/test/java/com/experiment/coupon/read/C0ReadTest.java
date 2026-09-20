package com.experiment.coupon.read;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * C0 — 캐시 없음
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.cache=C0")
class C0ReadTest extends AbstractCouponReadTest {

    @Override
    protected String expectedStrategy() {
        return "C0";
    }
}

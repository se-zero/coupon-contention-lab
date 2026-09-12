package com.experiment.coupon.concurrency;

import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * W4 — Redis 원자 연산 + 비동기 DB 반영
 *
 * 재고와 발급 명단이 Redis 에 있으므로 테스트마다 Redis 도 비운다.
 * 판정은 DB 를 보므로 워커가 큐를 다 비울 때까지 기다린 뒤 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "coupon.strategy=W4")
class W4ConcurrencyTest extends AbstractIssueConcurrencyTest {

    @Autowired
    RedissonClient redisson;

    @BeforeEach
    void resetRedis() {
        redisson.getKeys().flushall();
    }

    @Override
    protected String expectedStrategy() {
        return "W4";
    }

    // 워커는 커밋한 뒤에 큐에서 지우므로, 큐가 비었다 = 전부 DB 에 있다
    @Override
    protected void awaitConvergence() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> redisson.getList("coupon:w4:queue", StringCodec.INSTANCE).isEmpty());
    }
}

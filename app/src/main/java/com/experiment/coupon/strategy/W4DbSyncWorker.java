package com.experiment.coupon.strategy;

import com.experiment.coupon.metrics.IssueMetrics;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W4 반영 워커 — Redis 큐의 발급 사실을 DB 에 뒤따라 쓴다
 *
 * 같은 JVM 의 스레드 하나다. W4 가 아닐 때는 만들지 않는다 —
 * 빈 큐를 100ms 마다 읽는 명령이 다른 전략의 Redis 지표에 섞인다.
 *
 * 순서: 읽기 -> INSERT 묶음 + 카운터 증가 -> 커밋 -> 큐에서 제거.
 * 커밋 전에 죽으면 다음 주기에 같은 항목을 다시 쓴다 (at-least-once).
 * 그때의 중복 INSERT 는 ON CONFLICT 가 흡수한다.
 */
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "W4")
public class W4DbSyncWorker {

    // 확정: 100ms 마다 최대 500건. 1주차 W0 기준 100ms 에 26건이므로 쌓이면 그것도 결과다
    private static final int BATCH_SIZE = 500;

    private final RList<String> queue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final IssueMetrics metrics;

    public W4DbSyncWorker(RedissonClient redisson,
                          JdbcTemplate jdbc,
                          TransactionTemplate transactionTemplate,
                          IssueMetrics metrics) {
        this.queue = redisson.getList(W4RedisAtomicStrategy.QUEUE_KEY, StringCodec.INSTANCE);
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 100)
    public void drain() {
        List<String> items = queue.range(0, BATCH_SIZE - 1);
        if (items.isEmpty()) {
            metrics.recordQueueSize(0);
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            int[] inserted = jdbc.batchUpdate(
                    "INSERT INTO coupon_issue (coupon_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    items, items.size(), (ps, item) -> {
                        String[] parts = item.split(":");
                        ps.setLong(1, Long.parseLong(parts[0]));
                        ps.setLong(2, Long.parseLong(parts[1]));
                    })[0];

            // 실제로 들어간 행만큼만 카운터를 올린다 — ON CONFLICT 로 흡수된 건은 세지 않는다
            Map<Long, Integer> insertedPerCoupon = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                if (inserted[i] == 1) {
                    long couponId = Long.parseLong(items.get(i).split(":")[0]);
                    insertedPerCoupon.merge(couponId, 1, Integer::sum);
                }
            }
            insertedPerCoupon.forEach((couponId, n) ->
                    jdbc.update("UPDATE coupon SET issued_count = issued_count + ? WHERE id = ?", n, couponId));
        });

        queue.trim(items.size(), -1);  // 커밋 후 제거
        metrics.recordReflected(items.size());
        metrics.recordQueueSize(queue.size());
    }
}

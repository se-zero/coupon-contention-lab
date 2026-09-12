package com.experiment.coupon.strategy;

import com.experiment.coupon.domain.Coupon;
import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.repository.CouponRepository;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * W4 — Redis 원자 연산 (Lua 스크립트 + 비동기 DB 반영)
 *
 * 예상 실패 모드: Redis 장애 시 정합성 공백, 반영 지연 (PROJECT_BRIEF 5.1)
 * 재고와 발급 명단이 Redis 에 있고 DB 는 워커가 뒤따라 채우는 사본이다.
 * "발급됐다"는 사실이 Redis 에만 있는 구간이 생기며, 이는 관측 대상이다. (작업 규칙 4, NFR-05)
 *
 * 요청 경로에 DB 가 없다. 예외는 재고 미적재 시 1회 조회뿐이다.
 * 검증 순서(기간 -> 소진 -> 중복)는 IssueCore 와 같다. (PROJECT_BRIEF 5.4)
 */
@Component
public class W4RedisAtomicStrategy implements CouponIssueStrategy {

    // 워커가 같은 키를 읽는다
    static final String QUEUE_KEY = "coupon:w4:queue";

    // 반환 코드
    private static final long ISSUED = 1;
    private static final long NOT_LOADED = 0;
    private static final long NOT_IN_PERIOD = -1;
    private static final long SOLD_OUT = -2;
    private static final long DUPLICATE = -3;

    // KEYS: 쿠폰 해시 / 발급 명단 / 반영 큐,  ARGV: userId / now / 큐 항목
    // 차감·명단 등록·큐 적재가 한 스크립트 안이라 "차감됐는데 큐에 없는" 상태가 없다
    private static final String ISSUE_SCRIPT = """
            local c = redis.call('hmget', KEYS[1], 'stock', 'start', 'end')
            if not c[1] then return 0 end
            local now = tonumber(ARGV[2])
            if now < tonumber(c[2]) or now > tonumber(c[3]) then return -1 end
            if tonumber(c[1]) <= 0 then return -2 end
            if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return -3 end
            redis.call('hincrby', KEYS[1], 'stock', -1)
            redis.call('sadd', KEYS[2], ARGV[1])
            redis.call('rpush', KEYS[3], ARGV[3])
            return 1
            """;

    // 적재 — 이미 있으면 덮어쓰지 않는다 (차감이 진행된 값을 되돌리면 안 된다)
    private static final String LOAD_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 1 then return 0 end
            redis.call('hset', KEYS[1], 'stock', ARGV[1], 'start', ARGV[2], 'end', ARGV[3])
            return 1
            """;

    private final RScript script;
    private final CouponRepository couponRepository;

    public W4RedisAtomicStrategy(RedissonClient redisson, CouponRepository couponRepository) {
        this.script = redisson.getScript(StringCodec.INSTANCE);
        this.couponRepository = couponRepository;
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        long code = tryIssue(couponId, userId);
        if (code == NOT_LOADED) {
            // 첫 요청 또는 Redis 초기화 직후 — DB 에서 재고를 가져와 적재하고 다시 시도
            if (!load(couponId)) {
                return IssueResult.COUPON_NOT_FOUND;
            }
            code = tryIssue(couponId, userId);
        }
        if (code == ISSUED) return IssueResult.ISSUED;
        if (code == NOT_IN_PERIOD) return IssueResult.NOT_IN_PERIOD;
        if (code == SOLD_OUT) return IssueResult.SOLD_OUT;
        if (code == DUPLICATE) return IssueResult.DUPLICATE;
        throw new IllegalStateException("unexpected script result: " + code);
    }

    private long tryIssue(long couponId, long userId) {
        return script.eval(RScript.Mode.READ_WRITE, ISSUE_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(couponKey(couponId), issuedKey(couponId), QUEUE_KEY),
                String.valueOf(userId), String.valueOf(epochSecond(LocalDateTime.now())), couponId + ":" + userId);
    }

    // 요청 경로에서 DB 를 만나는 유일한 지점
    private boolean load(long couponId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            return false;
        }
        script.eval(RScript.Mode.READ_WRITE, LOAD_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(couponKey(couponId)),
                String.valueOf(coupon.remaining()),
                String.valueOf(epochSecond(coupon.getStartAt())),
                String.valueOf(epochSecond(coupon.getEndAt())));
        return true;
    }

    // IssueValidator 의 LocalDateTime 비교와 같은 척도 (벽시계 시각을 UTC 로 간주)
    private static long epochSecond(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC);
    }

    private static String couponKey(long couponId) {
        return "coupon:w4:" + couponId;
    }

    private static String issuedKey(long couponId) {
        return "coupon:w4:" + couponId + ":issued";
    }

    @Override
    public String type() {
        return "W4";
    }
}

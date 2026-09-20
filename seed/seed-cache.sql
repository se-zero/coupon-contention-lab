-- 실험 B 전용 시드 — 캐시 쿠폰 20개 (확정값 D-12)
--
-- seed.sql 은 건드리지 않는다. 실험 A 의 시드가 바뀌면 이미 끝난 측정과의
-- 비교 조건이 깨지고 verify.sql 출력 행 수도 달라진다. 그래서 이 파일은 별도로 둔다 —
-- run-cache.ps1 이 reset.ps1(= seed.sql 재주입 + Redis FLUSHALL) 을 돌린 "뒤에" 이 파일을 적용한다.
--
-- TRUNCATE 하지 않는다 (reset.ps1 이 이미 했다). INSERT 만 한다.

INSERT INTO coupon (id, name, total_quantity, issued_count, start_at, end_at)
SELECT
    id,
    'cache-coupon-' || lpad((id - 100)::text, 2, '0'),
    100000,
    (id - 100) * 137,   -- 키마다 issued_count 를 다르게 넣는다 — 응답이 키마다 달라야 캐시가 키를 섞지 않는지 확인할 수 있다
    now() - interval '1 hour',
    now() + interval '30 days'
FROM generate_series(101, 120) AS id;

SELECT setval('coupon_id_seq', 120, true);

SELECT id, name, total_quantity, issued_count FROM coupon WHERE id BETWEEN 101 AND 120 ORDER BY id;

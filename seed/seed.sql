-- 시드 데이터 (확정값 D-02, D-03)
--
-- 매 실행 전 초기화 규칙(PROJECT_BRIEF 7장)에 따라 전체를 비우고 다시 넣는다.
-- 쿠폰 id 는 부하 스크립트가 참조하므로 고정한다.

TRUNCATE coupon_issue, coupon RESTART IDENTITY CASCADE;

INSERT INTO coupon (id, name, total_quantity, issued_count, start_at, end_at) VALUES
    -- spike / chaos 용 — 재고가 소진되어야 NFR-01/02/04 를 검증할 수 있다
    (1, 'spike-coupon',     100000,  0, now() - interval '1 hour', now() + interval '30 days'),
    -- ramp / soak 용 — 측정 중 소진되면 워크로드가 '발급'에서 '거절'로 바뀌어 비교가 깨진다
    -- 20,000,000: W4 가 30분에 600~700만 건을 발급하므로 500만이면 soak 도중 매진된다 (D-03 개정)
    (2, 'sustained-coupon', 20000000, 0, now() - interval '1 hour', now() + interval '30 days');

SELECT setval('coupon_id_seq', 2, true);

SELECT id, name, total_quantity, issued_count FROM coupon ORDER BY id;

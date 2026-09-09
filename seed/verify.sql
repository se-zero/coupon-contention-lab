-- NFR-01 / NFR-02 판정 쿼리
--
-- 부하 실행 직후 돌린다. over_issue 또는 duplicate_users 가 0 이 아니면 해당 전략은 즉시 탈락이다.

SELECT c.id,
       c.name,
       c.total_quantity,
       c.issued_count                                   AS counter_value,
       (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id) AS actual_rows,
       -- NFR-01: 실제 발급 행 수가 재고를 넘었는가
       GREATEST((SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id) - c.total_quantity, 0) AS over_issue,
       -- 카운터와 실제 행 수의 불일치 (W4 비동기 반영 지연 확인용)
       c.issued_count - (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id) AS counter_drift,
       -- NFR-02: (coupon_id, user_id) 중복
       (SELECT COALESCE(sum(cnt - 1), 0)
        FROM (SELECT count(*) AS cnt FROM coupon_issue i
              WHERE i.coupon_id = c.id GROUP BY i.user_id HAVING count(*) > 1) d) AS duplicate_users
FROM coupon c
ORDER BY c.id;

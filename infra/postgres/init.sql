-- 스키마 소유자는 이 파일이다. 애플리케이션은 ddl-auto=validate 로 검증만 한다.
-- 최초 기동(빈 볼륨) 시 1회만 실행된다. 데이터 초기화는 seed/reset.ps1 을 쓴다.

CREATE TABLE IF NOT EXISTS coupon (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    total_quantity INTEGER      NOT NULL,
    issued_count   INTEGER      NOT NULL DEFAULT 0,
    -- W2 낙관적 락용. @Version 으로 매핑하지 않는다 — 붙이면 전 전략에 암묵 적용된다 (PROJECT_BRIEF 5.2)
    version        BIGINT       NOT NULL DEFAULT 0,
    start_at       TIMESTAMP    NOT NULL,
    end_at         TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS coupon_issue (
    id        BIGSERIAL PRIMARY KEY,
    coupon_id BIGINT    NOT NULL REFERENCES coupon (id),
    user_id   BIGINT    NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT now(),
    -- NFR-02 의 최후 방어선. 모든 전략에서 제거하지 않는다 (PROJECT_BRIEF 5.4)
    CONSTRAINT uk_coupon_issue_coupon_user UNIQUE (coupon_id, user_id)
);

-- 정합성 검증 쿼리(쿠폰별 발급 건수 집계)용
CREATE INDEX IF NOT EXISTS idx_coupon_issue_coupon_id ON coupon_issue (coupon_id);

-- pg_stat_statements — 쿼리별 실행 시간/호출 수 관측 (PROJECT_BRIEF 7장)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

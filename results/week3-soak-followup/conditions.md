# 측정 조건 — 3주차 6단계 (2주차 soak 계단 하락 후속, DB 계측)

> 2주차 `soak` 에서 W0 · W1 이 11~24분 사이 한 단계(−25%) 떨어지는 계단 하락을 3/3 재현했다
> (`results/week2-experiment-a/summary.md` 6장). 원인 후보(체크포인트 · autovacuum)를 계측해
> 하락 시점과 대조한다. **조건은 2주차(1단계)와 동일하다** — 다른 것은 DB 계측뿐이다.

## 고정 조건 (2주차 1단계와 동일)

인스턴스 수 1, DB 커넥션 총량 30, PostgreSQL `max_connections` 100, 톰캣 스레드 200,
JVM 힙 `-Xms1g -Xmx1g`, OSIV off, `soak` = 200 VU × 30분, 같은 호스트. 나머지 스펙(하드웨어,
런타임 버전, 시드 쿠폰)은 `results/week2-experiment-a/conditions.md` 를 그대로 따른다 — 여기
다시 적지 않는다.

## 추가한 것 — DB 계측 (3주차 6단계)

| 항목 | 방식 |
|---|---|
| 체크포인트 (`pg_stat_bgwriter_checkpoints_timed/req_total`, `buffers_checkpoint_total`, `checkpoint_write_time_total`) | **내장 컬렉터 그대로.** `postgres_exporter:v0.15.0` 은 `stat_bgwriter` 컬렉터가 기본 활성이고, PG16 은 체크포인트 열이 아직 `pg_stat_bgwriter` 에 있어 플래그 변경 없이 나온다. `docker-compose.yml` 변경 없음 |
| `pg_stat_user_tables` (n_live_tup · n_dead_tup · autovacuum_count · last_autovacuum, `coupon`/`coupon_issue`) | **내장 컬렉터 그대로.** `stat_user_tables` 컬렉터도 기본 활성. `infra/postgres/queries.yml` 변경 없음 |
| `pg_stat_database` (xact_commit · blks_read · blks_hit) | **내장 컬렉터 그대로.** `stat_database` 컬렉터도 기본 활성 |
| Grafana 패널 | `infra/grafana/dashboards/experiment-a.json` 에 2행(y=29, y=36) 4패널 추가 — 체크포인트, autovacuum/dead tuple, 커밋/초, 버퍼 캐시 히트율. 기존 9패널·`queries.yml` 의 기존 두 쿼리는 그대로 |

**실측으로 확인한 것** (커스텀 쿼리 추가 없이 11개 메트릭 전부 나옴): exporter 는 아무 플래그도
받지 않은 기본 상태로 `pg_stat_bgwriter_*` · `pg_stat_user_tables_*` · `pg_stat_database_*` 를
전부 낸다. 세 내장 컬렉터(`stat_bgwriter`/`stat_database`/`stat_user_tables`)가 v0.15.0 기본값
`enabled` 이기 때문이다. `queries.yml` 과 `docker-compose.yml` 의 exporter 서비스는 **수정하지
않았다.**

## 앱 · 부하 · 시드 코드

`loadtest/`, `seed/` 는 `fff48c3` 과 diff 없음. `app/` 은 파일 2개가 다르다 —
`W4DbSyncWorker.java` / `application.yml` 에 `coupon.w4.worker-enabled` 토글
(3주차 1단계 `6f37232`, D-06, 다중 인스턴스에서 W4 워커 중복 실행을 막는 장치). 기본값
`true` 로 기존과 동일하게 동작하고, W0 · W1 · W3 전략 경로에는 아무 영향이 없다(조건부
빈 하나가 `@ConditionalOnProperty(strategy=W4)` 뒤에 추가로 걸릴 뿐이다). 실제 확인은
측정 후 summary 에서 `git diff fff48c3 <측정 커밋> -- app loadtest seed` 로 다시 한다.

## 실행 목록

2주차 폴더의 회차 번호를 이어받는다 (D-05) — W0·W1 은 이미 3회, W3 는 1회뿐이었다.

| 전략 | 시나리오 | 회차 | 비고 |
|---|---|---|---|
| W3 | soak | 2, 3 | 2주차 1회차(하락 없음)에 2·3회차를 채워 3회로 마감 |
| W0 | soak | 4 | 계측 붙인 채 1회 추가 |
| W1 | soak | 4 | 계측 붙인 채 1회 추가 |

**4회** — 약 2.2시간 (무인).

## 판정 기준

- **NFR-06** — 계단 하락 여부 · 시점 (2주차와 같은 방법: 앞뒤 5분 이상 구간의 낙차가 가장 큰 지점)
- **원인 대조** — 하락 시점과 체크포인트 완료(`checkpoints_timed/req_total` 증가 시각) ·
  autovacuum 실행(`last_autovacuum` 갱신 시각) 시각이 맞는지. 맞으면 원인, 맞지 않으면
  "갈리지 않았다" 로 적는다 (`plan/week3.md` 6단계)

## 알려진 한계

1. **Prometheus 스크레이프 간격은 바꾸지 않았다.** postgres job 은 전역 설정을 상속하며
   `/api/v1/targets` 실측으로 **5초** 임을 확인했다(`infra/prometheus/prometheus.yml` 의
   `global.scrape_interval: 5s`, job 별 override 없음) — 이벤트 시각 해상도가 5초라는
   한계가 있다.
2. 나머지 한계는 2주차 `conditions.md` 와 동일 (WSL2 fsync, k6 컨테이너 실행 등).

## 측정에 쓴 커밋

`runs.tsv` 의 `commit` 열에 자동 기록된다. 4행의 값이 전부 같은지 확인한다.

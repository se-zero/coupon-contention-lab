# 선착순 발급 시스템 — 쓰기/읽기 병목 비교 실험

> 기능 완성이 목적이 아니라, **비기능 요구사항(정합성·응답 시간·장애 내성)을 만족하는 설계가 무엇인지를 측정으로 증명하는 것**이 목적이다.
>
> 프로젝트의 단일 기준(SSOT)은 [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) 다. 작업 전에 먼저 읽는다.

## 진행 상황

| 주차 | 목표 | 상태 |
|---|---|---|
| 1주차 | 요구사항 확정 + 도메인 + Docker Compose + 측정 환경 | **완료** |
| 2주차 | 실험 A 1단계 — W0~W4 구현 및 성능 측정 | |
| 3주차 전반 | 실험 A 2단계 — 3 인스턴스 정합성 검증 | |
| 3주차 후반 | 실험 B — C0~C4 구현 및 측정 | |
| 4주차 | 결과 정리, 리포트, 회고 | |

현재 구현된 전략은 **W0(JVM 로컬 락)** 하나다. W1~W4는 2주차에 같은 인터페이스로 추가된다.

## 실행

### 1. 스택 기동

```powershell
docker compose up -d --build
```

| 서비스 | 주소 | 용도 |
|---|---|---|
| app | http://localhost:8080 | 애플리케이션 |
| Grafana | http://localhost:3000 | 대시보드 (익명 접속) |
| Prometheus | http://localhost:9090 | 메트릭 |
| PostgreSQL | localhost:5432 | `coupon` / `coupon` |
| Redis | localhost:6379 | 2주차부터 사용 |

### 2. 시드 주입

```powershell
.\seed\reset.ps1
```

| 쿠폰 | id | 재고 | 용도 |
|---|---|---|---|
| `spike-coupon` | 1 | 100,000 | spike — 소진시켜 NFR-01/02/04 검증 |
| `sustained-coupon` | 2 | 5,000,000 | ramp / soak — 소진되지 않음 |

### 3. 부하 실행

```powershell
k6 run loadtest/scenarios/spike.js
```

Grafana의 **"실험 A — 쓰기 경로"** 대시보드에서 처리량 / P99 / HikariCP `pending` 을 함께 본다.

### 4. 정합성 판정

```powershell
.\seed\verify.ps1
```

`over_issue`(NFR-01) 또는 `duplicate_users`(NFR-02)가 **1건이라도 0이 아니면 해당 전략은 탈락**이다.

### 전략 교체

```powershell
$env:COUPON_STRATEGY = 'W1'      # 2주차부터
docker compose up -d --force-recreate app
```

`DB_POOL_SIZE` 도 같은 방식으로 덮어쓴다 (2단계에서 인스턴스당 10으로 내릴 때 사용).
값을 주지 않으면 `COUPON_STRATEGY=W0`, `DB_POOL_SIZE=30` 이 적용된다.

## 확정된 측정 조건

변경하면 이전 측정치를 전부 폐기하고 재측정해야 한다. (PROJECT_BRIEF 12장)

| 항목 | 값 |
|---|---|
| DB 커넥션 총량 | **30** (1단계 풀 30 / 2단계 인스턴스당 10 × 3대) |
| PostgreSQL `max_connections` | 100 |
| 톰캣 스레드 수 | 200 (전 비교군 동일) |
| JVM 힙 | `-Xms1g -Xmx1g` (전 비교군 동일) |
| 쿠폰 재고 / 총 요청 | 100,000 / 1,000,000 (경합비 10:1) |

## 구조

```
├── PROJECT_BRIEF.md      # SSOT — 요구사항, 실험 설계, 확정 결정
├── docs/                 # CS 개념 정리 (측정 결과 해석용 배경 지식)
├── app/                  # Spring Boot 애플리케이션
│   └── src/main/java/com/experiment/coupon/
│       ├── domain/       # Coupon, CouponIssue, IssueResult
│       ├── strategy/     # CouponIssueStrategy (W0~W4), 공통 검증/발급 본문
│       ├── service/      # 전략 선택 + 메트릭 집계
│       └── api/          # REST 엔드포인트
├── infra/                # docker compose 구성 요소 (postgres, prometheus, grafana)
├── seed/                 # 시드 주입 / 정합성 판정 스크립트
├── loadtest/             # k6 부하 스크립트
├── results/              # 측정 원본 (주차별) — 4주차 리포트의 근거
└── run-experiment.ps1    # 측정 1회 실행 (초기화 → 부하 → 판정 → 저장)
```

## 측정 실행

측정은 **반드시 `run-experiment.ps1` 로 한다.** 손으로 k6 를 돌리면 초기화를 빠뜨려 결과가 오염된다.

```powershell
.un-experiment.ps1 -Strategy W0 -Scenario spike -Run 1
```

결과는 `results/<주차>/` 에 원본(k6 JSON)·로그·정합성 판정으로 남는다. 자세한 규칙은 [`results/README.md`](results/README.md) 참조.

## API

| Method | Path | FR | 실험 |
|---|---|---|---|
| POST | `/api/coupons/{id}/issue` | FR-01, 02, 05 | 실험 A (쓰기) |
| GET | `/api/coupons/{id}` | FR-03 | 실험 B (읽기) |
| GET | `/api/coupons` | FR-04 | 실험 B (읽기) |

응답의 `result` 는 `ISSUED` / `SOLD_OUT` / `DUPLICATE` / `NOT_IN_PERIOD` / `COUPON_NOT_FOUND` 중 하나다.
비즈니스 거절은 4xx로 내려간다. **5xx는 NFR-04 위반 신호**이므로 정상 경로에 두지 않는다.

## 테스트

```powershell
cd app; .\gradlew test
```

Testcontainers로 PostgreSQL을 띄워 NFR-01(초과 발급 0건), NFR-02(1인 1매)를 검증한다. Docker가 실행 중이어야 한다.

# 실험 기록

측정 원본을 주차별로 남긴다. 4주차 리포트는 **재측정 없이 이 폴더만 보고 쓸 수 있어야 한다.**

## 구조

```
results/
└── week2-experiment-a/
    ├── conditions.md            # 측정 조건 (환경 스펙, 확정값, 알려진 한계)
    ├── runs.tsv                 # 실행 이력 인덱스 (자동 기록)
    ├── raw/W0-spike-run1.json   # k6 요약 원본
    ├── log/W0-spike-run1.txt    # k6 터미널 출력
    ├── integrity/W0-spike-run1.txt  # verify.sql 출력 (NFR-01/02 판정)
    ├── charts/ramp-comparison-light.png  # 그래프 (라이트·다크 한 쌍) — tools/make-charts.py 가 raw/ 에서 다시 그린다
    └── summary.md               # 3회 평균·편차 표 + 해석
└── week3-experiment-b/
    ├── series/C1-run1.tsv       # 1초 시계열 (실험 B 는 이 파일이 원본 — Prometheus 보존 기간과 무관하게 다시 그린다)
    └── charts/                  # python tools/make-charts.py --week week3-experiment-b
└── week2-experiment-a-proxy/    # 조건이 달랐던 첫 41회 (호스트 k6, 프록시 경유). 정식 아님 — 규칙 2
```

파일명은 `<전략>-<시나리오>-run<회차>` 로 고정한다.

## 스택 준비 — 재현

아래 러너가 스택 기동부터 판정까지 대신한다. 손으로 확인하고 싶을 때의 순서다. **Windows PowerShell 기준**이며 compose 명령 자체는 OS 와 무관하다.

### 1. 스택 기동

```powershell
docker compose up -d --build                                                             # 1대 — 1단계 · 실험 B
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale app=2     # 3대 — 2단계 (app ×2 + app-worker + nginx)
```

| 서비스 | 주소 | 용도 |
|---|---|---|
| app | http://localhost:8080 | 애플리케이션 (3대 스택에서는 nginx 가 8080 을 받는다) |
| Grafana | http://localhost:3000 | 대시보드 "실험 A — 쓰기 경로" · "실험 B — 읽기 경로" (익명 접속) |
| Prometheus | http://localhost:9090 | 메트릭 (보존 30일) |
| PostgreSQL | localhost:5432 | `coupon` / `coupon` |
| Redis | localhost:6379 | 분산 락 (W3) · 원자 연산 (W4) · 캐시 (C1~C4) |

### 2. 시드 주입

```powershell
.\seed\reset.ps1        # seed.sql 재주입 + Redis FLUSHALL — 매 측정 전에 반드시
```

| 쿠폰 | id | 재고 | 용도 |
|---|---|---|---|
| `spike-coupon` | 1 | 100,000 | spike / chaos — 소진시켜 NFR-01/02/04 검증 |
| `sustained-coupon` | 2 | 20,000,000 | ramp / soak — 소진되지 않음 |
| `cache-coupon-01~20` | 101~120 | `seed/seed-cache.sql` | 실험 B — `run-cache.ps1` 이 reset 뒤에 주입한다 |

### 3. 전략 교체

```powershell
$env:COUPON_STRATEGY = 'W1'                      # 쓰기 전략 W0~W4 (기본 W0)
$env:COUPON_CACHE = 'C3'                         # 읽기 전략 C0~C4 (기본 C0)
docker compose up -d --force-recreate app
```

`DB_POOL_SIZE`(기본 30) 도 같은 방식이다. 3대 스택은 오버레이가 인스턴스당 10 으로 고정한다 (D-01).

### 4. 정합성 판정

```powershell
.\seed\verify.ps1
```

`over_issue`(NFR-01) 또는 `duplicate_users`(NFR-02) 가 **1건이라도 0 이 아니면 해당 전략은 탈락**이다.

### 5. 테스트

```powershell
cd app; .\gradlew test
```

Testcontainers 로 PostgreSQL 을 띄워 NFR-01/02 를 검증한다. Docker 가 실행 중이어야 한다.

### 6. 부하 배관 확인 (정식 측정 아님)

```powershell
docker compose --profile loadtest run --rm k6 run /scripts/scenarios/spike.js
```

k6 는 **compose 네트워크 안에서** 돌린다 (D-09). 호스트의 k6 로 `localhost:8080` 을 때리면 Docker Desktop 의 포트 프록시가 1,000 VU 동시 연결을 일부 거부하고 처리량을 30% 깎는다. Grafana 에 그래프가 뜨는지 보는 용도이고, 정식 측정은 아래 러너로만 한다.

## 실행 방법

측정은 **반드시 `run-experiment.ps1`(실험 A) · `run-cache.ps1`(실험 B) 로 한다.** 손으로 k6 를 돌리지 않는다.

```powershell
.\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1                                              # 1단계 (1대)
.\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1 -Instances 3 -Week week3-experiment-a-stage2  # 2단계 (3대)
.\run-experiment.ps1 -Strategy W4 -Scenario chaos -Run 1                                              # 부하 중 Redis 를 죽였다 살린다 (W3 · W4)
.\run-cache.ps1 -Cache C1 -Run 1                                                                      # 실험 B
.\run-week2.ps1 -Phase ramp1                                                                          # 주차 배치 (무인, 재개 가능). run-week3.ps1 -Phase stage2 | soak-followup | experiment-b
```

스크립트가 순서를 대신 지킨다: `전략 적용 → 앱 재기동 → 적용 확인 → DB/Redis 초기화 → k6 → verify.sql → 결과 저장`.

브리프 7장이 요구하는 **"매 실행 전 초기화"** 와 **"같은 조건 최소 3회 반복"** 을 사람이 기억하는 방식은 반복 횟수가 늘면 반드시 어긋난다. 실제로 리셋 없이 두 번 돌려 결과가 오염된 적이 있다.

## 기록 규칙

1. **원본은 지우지 않는다.** 해석이 바뀌어도 원본은 그대로 둔다.
2. **조건이 바뀌면 새 주차 폴더를 판다.** 1단계와 2단계의 결과를 한 폴더에 섞지 않는다 (브리프 작업 규칙 5).
3. **`summary.md` 에는 3회 측정의 평균과 편차를 함께 쓴다.** 1회 값만 쓰지 않는다.
4. **미달도 그대로 기록한다.** "W0 는 동시성 200부터 NFR-03 미달" 은 실패가 아니라 결과다.
5. `runs.tsv` 의 `commit` 열로 어떤 코드에서 측정했는지 추적한다.

## 그래프

Grafana 화면은 매번 캡처하지 않는다. Prometheus 보존 기간을 30일로 잡아 두었으므로
4주차에 리포트에 실을 구간만 골라 캡처한다. 원시 수치는 `raw/*.json` 에 남아 있으므로
그래프를 다시 그릴 수도 있다.

## 주차별 폴더

| 폴더 | 내용 | 단계 |
|---|---|---|
| `week1-measurement-setup/` | 측정 환경 구축 검증, W0 기준선 관측 | — |
| `week2-experiment-a/` | 실험 A 1단계 — W0~W4 성능 측정 | 단일 인스턴스 |
| `week3-experiment-a-stage2/` | 실험 A 2단계 — 3 인스턴스 정합성 | 3 인스턴스 |
| `week3-soak-followup/` | 2주차 soak 계단 하락 후속 — DB 계측 + W3 3회 | 단일 인스턴스 |
| `week3-experiment-b/` | 실험 B — C0~C4 캐시 | 단일 인스턴스 |

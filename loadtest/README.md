# 부하 스크립트

애플리케이션 코드와 분리해 둔다. (PROJECT_BRIEF 작업 규칙 6)

## 실행

컨테이너 k6 — **측정은 항상 이쪽** (확정값 D-09):

```powershell
docker compose --profile loadtest run --rm k6 run /scripts/scenarios/spike.js
docker compose --profile loadtest run --rm k6 run -e VUS=2000 /scripts/scenarios/ramp.js
```

호스트 k6 (`k6 run loadtest/scenarios/spike.js`) 는 스크립트 문법 확인 정도에만 쓴다. `localhost:8080` 은
Docker Desktop 의 Windows 포트 프록시를 거치는데, 1,000 VU 가 동시에 연결하는 순간 일부를 거부하고
(`connectex: actively refused`) 요청마다 홉이 하나 더 있어 처리량이 30% 낮게 나온다.
2주차 측정에서 spike 요청의 4~47% 가 앱에 닿지 못한 원인이었다 (results/week2-experiment-a/conditions.md 한계 10).

## 시나리오

| 파일 | 검증 | 대상 쿠폰 | 단계 |
|---|---|---|---|
| `spike.js` | NFR-01~04 | `spike-coupon` (재고 100,000) | 1·2단계 |
| `ramp.js` | NFR-03 (꺾이는 지점) | `sustained-coupon` (재고 20,000,000) | 1단계 |
| `soak.js` | NFR-06 (시간축 열화) | `sustained-coupon` | 1단계 |

| `chaos.js` | NFR-05 (Redis 장애) | `spike-coupon` | 1단계, W3/W4 만 |

`chaos.js` 의 부하는 spike 와 같다. Redis 를 죽이고 살리는 것은 `run-experiment.ps1` 이 한다 (확정값 D-07).

## 설계 규칙

- **요청 생성은 `lib/issue.js` 한 곳에만 둔다.** 시나리오별로 요청 형태가 달라지면 비교가 무의미해진다.
- **`user_id`는 `exec.scenario.iterationInTest` 로 전역 유니크하게 만든다.** `__VU`/`__ITER` 조합은 충돌 가능성이 있어 NFR-02 측정을 오염시킨다.
- **워밍업 구간은 별도 시나리오로 분리하고 `phase` 태그로 구분한다.** 임계값 판정은 `{phase:measure}` 에만 적용된다 (PROJECT_BRIEF 7장 "워밍업 구간 제외").
- **워밍업은 `sustained-coupon` 을 쓴다.** `spike-coupon` 재고를 건드리면 100,000 이라는 확정값이 흔들린다.
- **비즈니스 거절(4xx)은 실패가 아니다.** `http.setResponseCallback` 으로 201/400/404/409 를 정상 처리로 등록했다. NFR-04 판정은 `coupon_server_error`(5xx 만) 로 한다.
- **`http_req_failed` 는 5xx 만 세는 것이 아니다.** 응답을 못 받은 요청(연결 거부·타임아웃, status 0)도 센다. `http_req_failed` 가 `coupon_server_error` 보다 크면 그 차이는 **앱에 닿지 못한 요청**이다.

## 매 실행 전후 절차

```powershell
.\seed\reset.ps1                       # 1. DB/Redis 초기화 + 시드 (7장 규칙)
docker compose --profile loadtest run --rm k6 run /scripts/scenarios/spike.js   # 2. 부하
.\seed\verify.ps1                      # 3. NFR-01/02 판정
```

`verify.ps1` 의 `over_issue` 또는 `duplicate_users` 가 0이 아니면 해당 전략은 즉시 탈락이다.

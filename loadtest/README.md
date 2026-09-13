# 부하 스크립트

애플리케이션 코드와 분리해 둔다. (PROJECT_BRIEF 작업 규칙 6)

## 실행

네이티브 k6 (권장 — 결과가 터미널에 바로 남는다):

```powershell
k6 run loadtest/scenarios/spike.js
k6 run -e VUS=2000 loadtest/scenarios/ramp.js
k6 run -e DURATION=30m loadtest/scenarios/soak.js
```

컨테이너 k6 (2단계에서 nginx 뒤의 앱을 때릴 때):

```powershell
docker compose --profile loadtest run --rm k6 run /scripts/scenarios/spike.js
```

## 시나리오

| 파일 | 검증 | 대상 쿠폰 | 단계 |
|---|---|---|---|
| `spike.js` | NFR-01~04 | `spike-coupon` (재고 100,000) | 1·2단계 |
| `ramp.js` | NFR-03 (꺾이는 지점) | `sustained-coupon` (재고 20,000,000) | 1단계 |
| `soak.js` | NFR-06 (시간축 열화) | `sustained-coupon` | 1단계 |

`chaos.js`(NFR-05)는 Redis가 쓰기 경로에 들어오는 **2주차(W3/W4 구현)** 에 추가한다.
지금은 Redis가 발급 경로에 없어 강제 종료해도 아무 일이 일어나지 않는다.

## 설계 규칙

- **요청 생성은 `lib/issue.js` 한 곳에만 둔다.** 시나리오별로 요청 형태가 달라지면 비교가 무의미해진다.
- **`user_id`는 `exec.scenario.iterationInTest` 로 전역 유니크하게 만든다.** `__VU`/`__ITER` 조합은 충돌 가능성이 있어 NFR-02 측정을 오염시킨다.
- **워밍업 구간은 별도 시나리오로 분리하고 `phase` 태그로 구분한다.** 임계값 판정은 `{phase:measure}` 에만 적용된다 (PROJECT_BRIEF 7장 "워밍업 구간 제외").
- **워밍업은 `sustained-coupon` 을 쓴다.** `spike-coupon` 재고를 건드리면 100,000 이라는 확정값이 흔들린다.
- **비즈니스 거절(4xx)은 실패가 아니다.** `http.setResponseCallback` 으로 201/400/404/409 를 정상 처리로 등록했다. `http_req_failed` 는 5xx 만 센다 (NFR-04).

## 매 실행 전후 절차

```powershell
.\seed\reset.ps1                       # 1. DB/Redis 초기화 + 시드 (7장 규칙)
k6 run loadtest/scenarios/spike.js     # 2. 부하
.\seed\verify.ps1                      # 3. NFR-01/02 판정
```

`verify.ps1` 의 `over_issue` 또는 `duplicate_users` 가 0이 아니면 해당 전략은 즉시 탈락이다.

# 실험 1회 실행 (전략 적용 -> 초기화 -> 부하 -> [W4 반영 대기] -> 정합성 판정 -> 결과 저장)
#
# 절차를 사람이 순서대로 지키는 방식은 반복 횟수가 늘면 반드시 어긋난다.
# (실제로 리셋 없이 두 번 돌려 결과가 오염된 적이 있다. PROJECT_BRIEF 7장 규칙)
#
# 사용법:
#   .\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1
#   .\run-experiment.ps1 -Strategy W1 -Scenario ramp  -Run 2 -K6Env HOLD=1m
#   .\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1 -Instances 3 -Week week3-experiment-a-stage2
#   .\run-experiment.ps1 -Strategy W4 -Scenario chaos -Run 1 -K6Env VUS=200,TOTAL=20000 -ChaosKillAt 5000

param(
    [Parameter(Mandatory)][ValidateSet('W0', 'W1', 'W2', 'W3', 'W4')]
    [string]$Strategy,

    [Parameter(Mandatory)][ValidateSet('spike', 'ramp', 'soak', 'chaos')]
    [string]$Scenario,

    [Parameter(Mandatory)][ValidateRange(1, 10)]
    [int]$Run,

    # 결과가 쌓일 주차 폴더
    [string]$Week = 'week2-experiment-a',

    # 인스턴스 수 (확정값 D-10: 2단계는 3 고정). 풀은 30 / Instances 로 계산한다 (D-01) - 따로 넘기다 어긋나는 사고를 막는다
    [ValidateSet(1, 3)]
    [int]$Instances = 1,

    # k6 에 넘길 추가 변수 (예: VUS=500,TOTAL=50000) - 축소 실행용. 정식 측정은 기본값(D-02)을 쓴다
    [string[]]$K6Env = @(),

    # chaos 전용 - 발급이 이 건수에 도달하면 Redis 를 죽인다 (확정값 D-07: 재고의 절반). 축소 실행 시 함께 줄인다
    [int]$ChaosKillAt = 50000
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 2단계(3대) 안전장치 - 결과 폴더와 시나리오를 강제한다 (작업 규칙 5, D-09)
if ($Instances -eq 3 -and $Week -eq 'week2-experiment-a') {
    throw '-Instances 3 인데 -Week 가 기본값(week2-experiment-a)이다 - 2단계 결과가 1단계 폴더에 섞인다. -Week 를 지정해라'
}
if ($Instances -eq 3 -and $Scenario -eq 'chaos') {
    throw '-Instances 3 은 chaos 를 지원하지 않는다 - chaos 는 2단계 대상이 아니고 Get-IssuedCount 가 호스트 8081 을 읽는다'
}

# 풀 크기는 인스턴스 수에서 계산한다 (확정값 D-01: 총 30)
$pool = 30 / $Instances

# 2단계는 오버레이(docker-compose.scale.yml)를 겹친다 - 이후 모든 docker compose 호출이 이 집합을 쓴다
$composeArgs = @('-f', "$root\docker-compose.yml")
if ($Instances -eq 3) { $composeArgs += @('-f', "$root\docker-compose.scale.yml") }

# PowerShell 5.1 은 파이프에 걸린 네이티브 명령의 stderr 를 오류로 승격시킨다.
# docker / k6 는 진행 상황을 stderr 로 쓰므로 정상 동작이 실행 실패로 둔갑한다.
function Invoke-Native {
    param([Parameter(Mandatory)][scriptblock]$Command)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $prev }
}
# 앱 메트릭에서 누적 발급 수를 읽는다 (chaos 의 kill 시점 판단용). 못 읽으면 -1
function Get-IssuedCount {
    try {
        $text = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing -TimeoutSec 3).Content
        if ($text -match 'coupon_issue_total\{[^}]*result="ISSUED"[^}]*\}\s+([0-9.eE+\-]+)') { return [double]$Matches[1] }
    } catch { }
    return -1
}
$tag = "$Strategy-$Scenario-run$Run"

$weekDir = Join-Path $root "results\$Week"
$rawDir = Join-Path $weekDir 'raw'
$logDir = Join-Path $weekDir 'log'
$intDir = Join-Path $weekDir 'integrity'
foreach ($d in @($rawDir, $logDir, $intDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

$summaryJson = Join-Path $rawDir "$tag.json"
$k6Log = Join-Path $logDir "$tag.txt"
$chaosLog = Join-Path $logDir "$tag.chaos.txt"
$appLog = Join-Path $logDir "$tag.app.txt"
$integrityOut = Join-Path $intDir "$tag.txt"

Write-Host "=== [$tag] 시작 ===" -ForegroundColor Cyan
Write-Host "  전략=$Strategy  시나리오=$Scenario  회차=$Run  인스턴스=$Instances  풀=$pool  주차=$Week"

# ── 1. 전략 적용 후 스택 기동 ──────────────────────────────────────────
Write-Host '[1/6] 전략 적용 + 스택 기동' -ForegroundColor Cyan
$env:COUPON_STRATEGY = $Strategy
$env:DB_POOL_SIZE = $pool
if ($Instances -eq 3) {
    # app ×2(replica) + app-worker ×1 = 3대. nginx 는 [2/6] 에서 앱이 healthy 해진 뒤에 재기동한다
    Invoke-Native { docker compose @composeArgs up -d --force-recreate --scale app=2 app app-worker } | Out-Null
} else {
    Invoke-Native { docker compose @composeArgs up -d --force-recreate app } | Out-Null
}

# ── 2. 기동 대기 ───────────────────────────────────────────────────────
Write-Host '[2/6] 헬스체크 대기' -ForegroundColor Cyan
$instanceIds = @()
if ($Instances -eq 3) {
    # 3대는 호스트 8081 이 없다 - 컨테이너 ID로 각자의 헬스와 전략을 확인한다
    # app / app-worker 를 따로 조회해 순서를 보장한다 (순서: app 들, 그 다음 app-worker - per_instance 열 순서와 맞춘다)
    $appIds = @(Invoke-Native { docker compose @composeArgs ps -q app })
    $workerIds = @(Invoke-Native { docker compose @composeArgs ps -q app-worker })
    $instanceIds = $appIds + $workerIds
    if ($instanceIds.Count -ne 3) { throw "app/app-worker 컨테이너가 3개가 아니라 $($instanceIds.Count)개다" }

    $deadline = (Get-Date).AddMinutes(3)
    $allHealthy = $false
    while ((Get-Date) -lt $deadline) {
        $bad = 0
        foreach ($id in $instanceIds) {
            $status = Invoke-Native { docker inspect --format '{{.State.Health.Status}}' $id }
            if ($status -ne 'healthy') { $bad++ }
        }
        if ($bad -eq 0) { $allHealthy = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $allHealthy) { throw '앱 3대가 3분 안에 healthy 가 되지 않았다' }

    # 실제 적용된 전략 확인 (설정 오타로 다른 전략을 측정하는 사고 방지) - 3대 모두 요청한 전략과 같아야 한다
    # curl 출력은 수천 줄이라 배열로 캡처된다 - 배열에 -match 를 쓰면 $Matches 가 채워지지 않으므로 한 문자열로 합친다
    $strategies = @()
    foreach ($id in $instanceIds) {
        $text = (Invoke-Native { docker exec $id curl -s localhost:8081/actuator/prometheus }) -join "`n"
        if ($text -match 'strategy="([^"]+)"') { $strategies += $Matches[1] }
        else { throw "컨테이너 $id 에서 strategy 태그를 찾지 못했다" }
    }
    $mismatch = @($strategies | Where-Object { $_ -ne $Strategy })
    if ($mismatch) { throw "요청한 전략은 $Strategy 인데 3대의 적용 전략은 $($strategies -join ', ') 다" }
    Write-Host "      적용 확인 (3대): strategy=$Strategy"

    # nginx 는 기동 시점에 DNS 를 푼다 - 앱을 재생성한 뒤에는 재기동해야 죽은 주소를 안 가리킨다
    # --scale app=2 를 다시 안 주면 nginx 가 app 을 의존성으로 끌어들이며 compose 가 scale 을 파일 기본값(1)로
    # 되돌려 app-2 를 지운다 (--scale 은 up 호출 사이에 유지되지 않는다) - 실측으로 확인한 버그
    Invoke-Native { docker compose @composeArgs up -d --force-recreate --scale app=2 nginx } | Out-Null
    $nginxDeadline = (Get-Date).AddSeconds(30)
    $nginxReady = $false
    while ((Get-Date) -lt $nginxDeadline) {
        try {
            Invoke-WebRequest -Uri 'http://localhost:8080/api/coupons/1' -UseBasicParsing -TimeoutSec 3 | Out-Null
            $nginxReady = $true; break
        } catch { Start-Sleep -Seconds 2 }
    }
    if (-not $nginxReady) { throw 'nginx 재기동 뒤 GET /api/coupons/1 이 200 을 주지 않는다' }
    Write-Host '      nginx 재기동 확인: GET /api/coupons/1 -> 200'
} else {
    $deadline = (Get-Date).AddMinutes(3)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri 'http://localhost:8081/actuator/health' -UseBasicParsing -TimeoutSec 3 | Out-Null
            $ready = $true; break
        } catch { Start-Sleep -Seconds 3 }
    }
    if (-not $ready) { throw '앱이 3분 안에 기동하지 않았다' }

    # 실제 적용된 전략 확인 (설정 오타로 다른 전략을 측정하는 사고 방지)
    # 메트릭의 공통 태그를 읽는다 - 읽기 전용이라 데이터를 건드리지 않는다
    $metrics = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing -TimeoutSec 10).Content
    if ($metrics -match 'strategy="([^"]+)"') {
        $applied = $Matches[1]
        if ($applied -ne $Strategy) { throw "요청한 전략은 $Strategy 인데 실제 적용된 것은 $applied 다" }
        Write-Host "      적용 확인: strategy=$applied"
    } else {
        throw '메트릭에서 strategy 태그를 찾지 못했다'
    }
}

# ── 3. DB / Redis 초기화 ───────────────────────────────────────────────
Write-Host '[3/6] DB / Redis 초기화' -ForegroundColor Cyan
Invoke-Native { & "$root\seed\reset.ps1" } | Out-Null

# ── 4. 부하 실행 ───────────────────────────────────────────────────────
# k6 는 compose 네트워크 안의 컨테이너로 돈다 (확정값 D-09). 호스트에서 돌리면 Docker Desktop 의
# Windows 포트 프록시가 1,000 VU 동시 연결의 일부를 거부하고 처리량을 30% 깎는다 (conditions.md 한계 10)
Write-Host '[4/6] k6 실행 (컨테이너)' -ForegroundColor Cyan
$k6Image = 'grafana/k6:1.6.1'
$k6Network = 'coupon-experiment_default'
$baseUrl = if ($Instances -eq 3) { 'http://nginx:8080' } else { 'http://app:8080' }
$k6Args = @('run', '--rm', '--network', $k6Network,
    '-v', "$($root -replace '\\', '/')/loadtest:/scripts:ro",
    '-v', "$($rawDir -replace '\\', '/'):/out",
    '-e', "BASE_URL=$baseUrl",
    $k6Image, 'run', '--no-color', "--summary-export=/out/$tag.json")
foreach ($e in $K6Env) { $k6Args += @('-e', $e) }
$k6Args += "/scripts/scenarios/$Scenario.js"

$startedAt = Get-Date
if ($Scenario -ne 'chaos') {
    Invoke-Native { & docker @k6Args } | Tee-Object -FilePath $k6Log
    $k6Exit = $LASTEXITCODE
} else {
    # chaos - k6 를 백그라운드로 두고 발급 수를 지켜보다가 Redis 를 죽였다 살린다 (확정값 D-07)
    # 시간이 아니라 발급 수를 기준으로 하는 이유: W4 는 W3 보다 훨씬 빨라서 같은 시각에 죽이면
    # 한쪽은 매진 후, 한쪽은 판매 중이 된다
    $chaosDowntimeSec = 10
    $k6Err = "$k6Log.stderr"
    $proc = Start-Process -FilePath 'docker' -ArgumentList $k6Args -NoNewWindow -PassThru `
        -RedirectStandardOutput $k6Log -RedirectStandardError $k6Err
    $null = $proc.Handle   # 핸들을 미리 잡아두지 않으면 종료 뒤 ExitCode 가 비어 있다 (PS 5.1)
    # 앱의 발급 카운터는 워밍업(다른 쿠폰)도 센다. chaos 시나리오가 시작되는 시점(chaos.js startTime 35s)의
    # 값을 기준선으로 빼야 "spike-coupon 발급 N 건" 이 된다 - 안 빼면 워밍업 중에 죽인다
    Start-Sleep -Seconds 35
    $base = [math]::Max(0, (Get-IssuedCount))
    $killed = $false
    while (-not $proc.HasExited) {
        Start-Sleep -Seconds 1
        if ($killed) { continue }
        $issued = (Get-IssuedCount) - $base
        if ($issued -ge $ChaosKillAt) {
            $killAt = Get-Date
            Invoke-Native { docker compose @composeArgs kill redis } | Out-Null
            "$($killAt.ToString('s'))`tkill`tissued=$issued" | Out-File -FilePath $chaosLog -Encoding utf8
            Write-Host "      Redis kill  $($killAt.ToString('HH:mm:ss'))  (발급 $issued 건)" -ForegroundColor Yellow
            Start-Sleep -Seconds $chaosDowntimeSec
            Invoke-Native { docker compose @composeArgs start redis } | Out-Null
            $restartAt = Get-Date
            "$($restartAt.ToString('s'))`tstart`tdowntime_s=$chaosDowntimeSec" | Out-File -FilePath $chaosLog -Encoding utf8 -Append
            Write-Host "      Redis start $($restartAt.ToString('HH:mm:ss'))  (빈 상태로 재시작)" -ForegroundColor Yellow
            $killed = $true
        }
    }
    $proc.WaitForExit()
    $k6Exit = $proc.ExitCode
    # k6 의 진행 로그(stderr)를 본 로그 뒤에 붙인다
    if (Test-Path $k6Err) { Get-Content $k6Err | Add-Content -Path $k6Log; Remove-Item $k6Err }
    if (-not $killed) { throw "발급이 $ChaosKillAt 건에 도달하지 않아 Redis 를 죽이지 못했다. 무효 실행 - 기록하지 않는다" }
}
$elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)

# 앱 로그 - 5xx 의 예외 클래스는 여기에만 남는다 (컨테이너는 다음 실행에서 재생성되어 사라진다).
# 전체 로그는 수백 MB 라 보관하지 않고, 예외 클래스별 건수와 ERROR/WARN 앞부분만 남긴다
# @() 로 한 번 더 감싼다 - if/else 표현식 대입은 파이프라인을 거치므로 원소 1개짜리 배열이
# 스칼라 문자열로 접혀버린다. 그 상태로 @logServices 스플랫하면 문자 단위로 쪼개진다 (실측 버그)
$logServices = @(if ($Instances -eq 3) { @('app', 'app-worker') } else { @('app') })
$appErrLines = @(Invoke-Native {
    docker compose @composeArgs logs --no-color --no-log-prefix @logServices |
        findstr /C:"unexpected error" /C:" ERROR " /C:" WARN "
})
$byClass = $appErrLines | ForEach-Object { if ($_ -match 'unexpected error: (\S+)') { $Matches[1] } } |
    Group-Object | Sort-Object Count -Descending
@("unexpected_error_total`t$([int](($byClass | Measure-Object -Property Count -Sum).Sum))") +
    @($byClass | ForEach-Object { "$($_.Name)`t$($_.Count)" }) +
    @('', '--- first ERROR/WARN lines ---') +
    @($appErrLines | Select-Object -First 30) |
    Out-File -FilePath $appLog -Encoding utf8

# k6 종료 코드: 0=통과, 99=임계값 미달, 그 외=실행 실패
# 임계값 미달은 측정 결과이지 실행 실패가 아니므로 구분한다.
# 콘솔에 뜨는 "thresholds ... have been crossed" 는 k6 가 stderr 로 쓰는 정상 알림이다
$thresholds = switch ($k6Exit) {
    0 { 'PASS' }
    99 { 'FAIL' }
    default { throw "k6 실행 실패 (exit=$k6Exit). 로그 확인: $k6Log" }
}

# ── 5. W4 반영 대기 ────────────────────────────────────────────────────
# W4 는 DB 반영이 비동기라 부하 종료 직후에 판정하면 미반영 건이 "미달 발급"으로 보인다.
# 워커는 커밋한 뒤에 큐에서 지우므로 큐가 비면 전부 DB 에 있다. 이 대기 시간이 반영 지연 측정값이다.
$reflectWait = ''
if ($Strategy -eq 'W4') {
    Write-Host '[5/6] W4 반영 대기 (큐가 빌 때까지)' -ForegroundColor Cyan
    $waitStart = Get-Date
    # soak 뒤에는 큐가 수백만까지 쌓인다. 2주차 정식 측정에서 30분 soak 뒤 큐 890만, 비우는 데 39분
    # (워커 3,850건/초). 30분 상한은 그 실행을 무효 처리했다 — 실측의 2배 이상으로 잡는다
    $waitDeadline = (Get-Date).AddMinutes(90)
    while ($true) {
        $len = [int](Invoke-Native {
            docker compose @composeArgs exec -T redis redis-cli LLEN coupon:w4:queue
        })
        if ($len -eq 0) { break }
        if ((Get-Date) -gt $waitDeadline) { throw "90분 안에 반영이 끝나지 않았다 (남은 큐 $len)" }
        Start-Sleep -Seconds 1
    }
    $reflectWait = [math]::Round(((Get-Date) - $waitStart).TotalSeconds, 1)
    Write-Host "      반영 완료: ${reflectWait}초 (1초 단위 폴링이므로 그만큼의 오차가 있다)"
}

# ── 6. 정합성 판정 ─────────────────────────────────────────────────────
Write-Host '[6/6] NFR-01 / NFR-02 판정' -ForegroundColor Cyan
Invoke-Native {
    Get-Content "$root\seed\verify.sql" -Raw -Encoding UTF8 |
        docker compose @composeArgs exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1
} | Tee-Object -FilePath $integrityOut

# ── k6 가 센 것과 DB 가 기억하는 것 ────────────────────────────────────
# k6_issued = 사용자에게 "받았다" 고 답한 횟수, db_rows = 서버가 실제로 기억하는 발급 수.
# 평소엔 같다. W4 chaos 에서 갈리는 만큼이 유실된 발급이다 (verify.sql 만으로는 보이지 않는다)
$summary = Get-Content $summaryJson -Raw -Encoding UTF8 | ConvertFrom-Json
$k6Issued = 0; $serverError = 0
if ($summary.metrics.coupon_issued) { $k6Issued = [int]$summary.metrics.coupon_issued.count }
if ($summary.metrics.coupon_server_error) { $serverError = [int]$summary.metrics.coupon_server_error.count }
$dbRows = [int](Invoke-Native {
    docker compose @composeArgs exec -T postgres psql -U coupon -d coupon -tA -c 'SELECT count(*) FROM coupon_issue'
})
Write-Host "      k6 발급 $k6Issued / DB 행 $dbRows / 차이 $($k6Issued - $dbRows) / 5xx $serverError"

# ── 인스턴스별 분배 · 앱 5xx 집계 ───────────────────────────────────────
# per_instance: 각 인스턴스가 판정한 발급 요청 수 (coupon_issue_total, result 라벨 전부 합산) - 3대면 n1/n2/n3, 1대면 값 하나
# app_5xx: 앱이 만든 5xx 응답 수 (http_server_requests_seconds_count). k6 의 coupon_server_error 와의 차이가 nginx 가 만든 5xx 다
# curl 출력은 배열로 캡처되므로 한 문자열로 합쳐야 [regex]::Matches 가 동작한다
$perInstanceCounts = @()
$app5xxRaw = 0
if ($Instances -eq 3) {
    foreach ($id in $instanceIds) {
        $text = (Invoke-Native { docker exec $id curl -s localhost:8081/actuator/prometheus }) -join "`n"
        $issued = 0
        foreach ($m in [regex]::Matches($text, 'coupon_issue_total\{[^}]*\}\s+([0-9.eE+\-]+)')) { $issued += [double]$m.Groups[1].Value }
        $perInstanceCounts += [math]::Round($issued)
        foreach ($m in [regex]::Matches($text, 'http_server_requests_seconds_count\{[^}]*status="5\d\d"[^}]*\}\s+([0-9.eE+\-]+)')) { $app5xxRaw += [double]$m.Groups[1].Value }
    }
} else {
    $text = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing -TimeoutSec 10).Content
    $issued = 0
    foreach ($m in [regex]::Matches($text, 'coupon_issue_total\{[^}]*\}\s+([0-9.eE+\-]+)')) { $issued += [double]$m.Groups[1].Value }
    $perInstanceCounts += [math]::Round($issued)
    foreach ($m in [regex]::Matches($text, 'http_server_requests_seconds_count\{[^}]*status="5\d\d"[^}]*\}\s+([0-9.eE+\-]+)')) { $app5xxRaw += [double]$m.Groups[1].Value }
}
$app5xx = [int][math]::Round($app5xxRaw)
$perInstance = $perInstanceCounts -join '/'
$totalIssued = ($perInstanceCounts | Measure-Object -Sum).Sum
Write-Host "      인스턴스별 처리 건수: $perInstance (합계 $totalIssued) / app_5xx $app5xx"
if ($Instances -eq 3 -and $totalIssued -gt 0) {
    for ($i = 0; $i -lt $perInstanceCounts.Count; $i++) {
        $share = $perInstanceCounts[$i] / $totalIssued
        if ($share -lt 0.25) {
            Write-Host "      경고: 인스턴스 $($i + 1) 처리 비율 $([math]::Round($share * 100, 1))% (< 25%) - 3대 실험 유효성 기준 미달, summary 에서 제외 대상" -ForegroundColor Yellow
        }
    }
}

# ── 실행 이력 기록 ─────────────────────────────────────────────────────
$indexFile = Join-Path $weekDir 'runs.tsv'
if (-not (Test-Path $indexFile)) {
    "timestamp`tstrategy`tscenario`trun`tpool`telapsed_s`tthresholds`treflect_wait_s`tk6_issued`tdb_rows`tserver_error`tcommit`tinstances`tper_instance`tapp_5xx" |
        Out-File -FilePath $indexFile -Encoding utf8
}
$commit = Invoke-Native { git -C $root rev-parse --short HEAD 2>$null }
if (-not $commit) { $commit = 'uncommitted' }
# 커밋 안 된 변경이 있으면 표시한다 - "같은 커밋으로 측정했다" 는 확인이 이 열에 기대기 때문이다 (conditions.md)
# 측정 경로만 본다. results/ 를 포함하면 배치의 첫 실행이 runs.tsv 에 한 줄 붙이는 순간
# 그 뒤 실행이 전부 dirty 로 기록된다 - 정작 알고 싶은 것은 측정 코드가 커밋됐는지다
elseif (Invoke-Native {
    git -C $root status --porcelain --untracked-files=no -- app loadtest seed infra docker-compose.yml docker-compose.scale.yml run-experiment.ps1 run-week2.ps1 run-week3.ps1 2>$null
}) { $commit = "$commit-dirty" }
"$($startedAt.ToString('s'))`t$Strategy`t$Scenario`t$Run`t$pool`t$elapsed`t$thresholds`t$reflectWait`t$k6Issued`t$dbRows`t$serverError`t$commit`t$Instances`t$perInstance`t$app5xx" |
    Out-File -FilePath $indexFile -Encoding utf8 -Append

Write-Host "=== [$tag] 완료 — 임계값 $thresholds, ${elapsed}초 ===" -ForegroundColor Green
Write-Host "  요약  $summaryJson"
Write-Host "  로그  $k6Log"
Write-Host "  정합성 $integrityOut"
Write-Host "  앱 로그 $appLog"
if ($Scenario -eq 'chaos') { Write-Host "  chaos  $chaosLog" }

# git rev-parse 등 앞선 네이티브 호출의 종료 코드가 스크립트 결과로 새지 않게 한다
exit 0

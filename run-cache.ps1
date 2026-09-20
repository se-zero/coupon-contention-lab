# 실험 B 1회 실행 (캐시 전략 적용 -> 초기화 -> 부하 -> 피크 db_load 산출 -> 결과 저장)
#
# run-experiment.ps1 과 같은 구조다. 정합성 판정은 없다 — 읽기 부하라 쓰기가 없다 (브리프 3.4).
# 단일 인스턴스 고정 (D-12) — 오버레이(docker-compose.scale.yml)를 쓰지 않는다.
#
# 사용법:
#   .\run-cache.ps1 -Cache C0 -Run 1
#   .\run-cache.ps1 -Cache C1 -Run 2 -K6Env VUS=20,DURATION=40s

param(
    [Parameter(Mandatory)][ValidateSet('C0', 'C1', 'C2', 'C3', 'C4')]
    [string]$Cache,

    [Parameter(Mandatory)][ValidateRange(1, 10)]
    [int]$Run,

    # 결과가 쌓일 주차 폴더
    [string]$Week = 'week3-experiment-b',

    # k6 에 넘길 추가 변수 (예: VUS=20,DURATION=40s) - 축소 실행용. 정식 측정은 기본값(D-12)을 쓴다
    [string[]]$K6Env = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 단일 인스턴스 고정 — 오버레이 없이 기본 compose 파일만 쓴다 (브리프 3.4, D-12)
$composeArgs = @('-f', "$root\docker-compose.yml")

# PowerShell 5.1 은 파이프에 걸린 네이티브 명령의 stderr 를 오류로 승격시킨다.
# docker / k6 는 진행 상황을 stderr 로 쓰므로 정상 동작이 실행 실패로 둔갑한다. (run-experiment.ps1 그대로)
function Invoke-Native {
    param([Parameter(Mandatory)][scriptblock]$Command)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $prev }
}
# 정규식 대신 줄 단위로 파싱하는 이유 - uri 라벨 값에 {couponId} 같은 중괄호가 들어있어
# "[^}]*}" 정규식이 라벨 집합의 닫는 중괄호가 아니라 그 안쪽 중괄호에서 멈춘다 (실측 버그, run-experiment.ps1 그대로 복사).
# metric{labels} value 형태이므로 마지막 공백 뒤가 값이라는 점만 이용한다
function Get-MetricSum {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Prefix,   # 예: 'coupon_cache_total{'
        [string]$Contains = ''                   # 라벨 부분에 포함돼야 하는 문자열 (예: 'result="hit')
    )
    $sum = 0.0
    foreach ($line in $Text -split "`n") {
        $line = $line.Trim()
        if (-not $line -or $line.StartsWith('#') -or -not $line.StartsWith($Prefix)) { continue }
        if ($Contains -and $line -notlike "*$Contains*") { continue }
        $sp = $line.LastIndexOf(' ')
        if ($sp -lt 0) { continue }
        $sum += [double]$line.Substring($sp + 1)
    }
    return $sum
}
# Prometheus query_range 로 원시 카운터 시계열을 {timestamp -> 값} 으로 받는다.
# 라벨 조합이 여럿이면(예: 공통 태그 외 추가 라벨) 타임스탬프별로 합산한다
function Get-PromRangeSeries {
    param(
        [Parameter(Mandatory)][string]$Metric,
        [Parameter(Mandatory)][int64]$StartUnix,
        [Parameter(Mandatory)][int64]$EndUnix
    )
    $uri = "http://localhost:9090/api/v1/query_range?query=$Metric&start=$StartUnix&end=$EndUnix&step=1"
    $resp = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 30
    $json = $resp.Content | ConvertFrom-Json
    if ($json.status -ne 'success') { throw "$Metric 쿼리 실패" }
    $byTs = @{}
    foreach ($series in $json.data.result) {
        foreach ($point in $series.values) {
            $ts = [int64]$point[0]
            $val = [double]$point[1]
            if ($byTs.ContainsKey($ts)) { $byTs[$ts] += $val } else { $byTs[$ts] = $val }
        }
    }
    return $byTs
}
# 인접 값의 차이(초당 증가분)로 바꾼다. PromQL 의 rate/increase 는 평활화가 피크를 뭉갠다 (docs/cache-stampede.md 4장).
# $Grid 는 전체 타임스탬프 축 — 메트릭에 그 초의 값이 없으면 0 으로 채운 뒤 차이를 낸다 (시간축을 맞추기 위함)
function Get-DeltaByTs {
    param(
        [Parameter(Mandatory)][hashtable]$ByTs,
        [Parameter(Mandatory)][int64[]]$Grid
    )
    $deltas = @{}
    $prevVal = $null
    foreach ($ts in $Grid) {
        $val = 0.0
        if ($ByTs.ContainsKey($ts)) { $val = $ByTs[$ts] }
        if ($null -ne $prevVal) {
            $diff = $val - $prevVal
            if ($diff -lt 0) { $diff = 0 }   # 앱 재시작으로 카운터가 리셋된 경우 방어
            $deltas[$ts] = $diff
        }
        $prevVal = $val
    }
    return $deltas
}

$tag = "$Cache-run$Run"

$weekDir = Join-Path $root "results\$Week"
$rawDir = Join-Path $weekDir 'raw'
$logDir = Join-Path $weekDir 'log'
$seriesDir = Join-Path $weekDir 'series'
foreach ($d in @($rawDir, $logDir, $seriesDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

$summaryJson = Join-Path $rawDir "$tag.json"
$k6Log = Join-Path $logDir "$tag.txt"
$appLog = Join-Path $logDir "$tag.app.txt"
$seriesFile = Join-Path $seriesDir "$tag.tsv"

Write-Host "=== [$tag] 시작 ===" -ForegroundColor Cyan
Write-Host "  캐시=$Cache  회차=$Run  주차=$Week"

# ── 1. 전략 적용 후 스택 기동 ──────────────────────────────────────────
Write-Host '[1/8] 캐시 전략 적용 + 스택 기동' -ForegroundColor Cyan
$env:COUPON_CACHE = $Cache
$env:DB_POOL_SIZE = 30
# 쓰기 전략은 W0 고정 — 이 부하에는 발급 요청이 없어 호출되지 않는다 (D-12)
$env:COUPON_STRATEGY = 'W0'
Invoke-Native { docker compose @composeArgs up -d --force-recreate app } | Out-Null

# ── 2. 기동 대기 + 적용 확인 ─────────────────────────────────────────────
Write-Host '[2/8] 헬스체크 대기' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(3)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        Invoke-WebRequest -Uri 'http://localhost:8081/actuator/health' -UseBasicParsing -TimeoutSec 3 | Out-Null
        $ready = $true; break
    } catch { Start-Sleep -Seconds 3 }
}
if (-not $ready) { throw '앱이 3분 안에 기동하지 않았다' }

# 실제 적용된 캐시 전략 확인 (설정 오타로 다른 전략을 측정하는 사고 방지)
$metrics = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing -TimeoutSec 10).Content
if ($metrics -match 'cache="([^"]+)"') {
    $applied = $Matches[1]
    if ($applied -ne $Cache) { throw "요청한 캐시 전략은 $Cache 인데 실제 적용된 것은 $applied 다" }
    Write-Host "      적용 확인: cache=$applied"
} else {
    throw '메트릭에서 cache 태그를 찾지 못했다'
}

# ── 3. DB / Redis 초기화 + 캐시 시드 ─────────────────────────────────────
Write-Host '[3/8] DB / Redis 초기화' -ForegroundColor Cyan
Invoke-Native { & "$root\seed\reset.ps1" } | Out-Null

Write-Host '      실험 B 시드 적용 (id 101~120)' -ForegroundColor Cyan
Invoke-Native {
    Get-Content "$root\seed\seed-cache.sql" -Raw -Encoding UTF8 |
        docker compose @composeArgs exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1
} | Out-Null

# ── 4. Prometheus 확인 ────────────────────────────────────────────────
# 러너는 app 만 띄운다 - Prometheus 가 없어도 k6 는 그대로 끝까지 돌아 peak_db_load 와
# series/ 가 조용히 빈 채 7분이 통째로 무의미해진다 (9단계 검증에서 실측)
Write-Host '[4/8] Prometheus 확인' -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri 'http://localhost:9090/-/ready' -UseBasicParsing -TimeoutSec 5 | Out-Null
} catch {
    throw 'Prometheus 가 없다 (localhost:9090). 이 실험은 1초 시계열이 핵심 지표라 Prometheus 없이는 무의미하다. docker compose up -d 로 전체 스택을 먼저 올려라'
}

# ── 5. 부하 실행 ───────────────────────────────────────────────────────
# k6 는 컨테이너로 돈다 (확정값 D-09) - 호스트에서 돌리면 Windows 포트 프록시가 결과를 오염시킨다
Write-Host '[5/8] k6 실행 (컨테이너)' -ForegroundColor Cyan
$k6Image = 'grafana/k6:1.6.1'
$k6Network = 'coupon-experiment_default'
$k6Args = @('run', '--rm', '--network', $k6Network,
    '-v', "$($root -replace '\\', '/')/loadtest:/scripts:ro",
    '-v', "$($rawDir -replace '\\', '/'):/out",
    '-e', 'BASE_URL=http://app:8080',
    $k6Image, 'run', '--no-color', "--summary-export=/out/$tag.json")
foreach ($e in $K6Env) { $k6Args += @('-e', $e) }
$k6Args += '/scripts/scenarios/stampede.js'

$startedAt = Get-Date
Invoke-Native { & docker @k6Args } | Tee-Object -FilePath $k6Log
$k6Exit = $LASTEXITCODE
$endedAt = Get-Date
$elapsed = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)

# 앱 로그 - 5xx 의 예외 클래스는 여기에만 남는다 (컨테이너는 다음 실행에서 재생성되어 사라진다).
# 전체 로그는 보관하지 않고, 예외 클래스별 건수와 ERROR/WARN 앞부분만 남긴다
$appErrLines = @(Invoke-Native {
    docker compose @composeArgs logs --no-color --no-log-prefix app |
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
$thresholds = switch ($k6Exit) {
    0 { 'PASS' }
    99 { 'FAIL' }
    default { throw "k6 실행 실패 (exit=$k6Exit). 로그 확인: $k6Log" }
}

# ── 6. 피크 db_load 산출 + 미스 타이머 시계열 ─────────────────────────────
# db_load 가 이 실험의 핵심 지표다. load/wait 타이머도 같은 1초 해상도로 같이 받는다 -
# 정식 실행의 미스에는 워밍업(jit/warm, 콜드 스타트)과 파동(측정 구간)이 섞여 있어
# runs.tsv 의 전 구간 평균만으로는 파동 중 미스 창의 실측값을 알 수 없다 (11단계가 구간을 잘라 쓴다)
Write-Host '[6/8] 피크 db_load 산출 + 타이머 시계열 (Prometheus)' -ForegroundColor Cyan
$peakDbLoad = ''
try {
    $startUnix = [DateTimeOffset]::new($startedAt.ToUniversalTime()).ToUnixTimeSeconds()
    $endUnix = [DateTimeOffset]::new($endedAt.ToUniversalTime()).ToUnixTimeSeconds()
    # 전체 타임스탬프 축 — 5개 메트릭의 행을 여기 기준으로 맞춘다 (특정 초에 값이 없는 메트릭은 0)
    $grid = New-Object System.Collections.Generic.List[int64]
    for ($t = $startUnix; $t -le $endUnix; $t++) { $grid.Add($t) }

    $dbLoadDelta = Get-DeltaByTs -Grid $grid -ByTs (Get-PromRangeSeries -Metric 'coupon_cache_db_load_total' -StartUnix $startUnix -EndUnix $endUnix)
    $loadSumDelta = Get-DeltaByTs -Grid $grid -ByTs (Get-PromRangeSeries -Metric 'coupon_cache_load_seconds_sum' -StartUnix $startUnix -EndUnix $endUnix)
    $loadCountDelta = Get-DeltaByTs -Grid $grid -ByTs (Get-PromRangeSeries -Metric 'coupon_cache_load_seconds_count' -StartUnix $startUnix -EndUnix $endUnix)
    $waitSumDelta = Get-DeltaByTs -Grid $grid -ByTs (Get-PromRangeSeries -Metric 'coupon_cache_wait_seconds_sum' -StartUnix $startUnix -EndUnix $endUnix)
    $waitCountDelta = Get-DeltaByTs -Grid $grid -ByTs (Get-PromRangeSeries -Metric 'coupon_cache_wait_seconds_count' -StartUnix $startUnix -EndUnix $endUnix)

    $lines = @("t_offset_s`tdb_load_per_s`tload_sum_delta`tload_count_delta`twait_sum_delta`twait_count_delta")
    $peak = 0.0
    for ($i = 1; $i -lt $grid.Count; $i++) {
        $ts = $grid[$i]
        $offset = $ts - $startUnix
        $d = $dbLoadDelta[$ts]
        $lines += "$offset`t$d`t$($loadSumDelta[$ts])`t$($loadCountDelta[$ts])`t$($waitSumDelta[$ts])`t$($waitCountDelta[$ts])"
        if ($d -gt $peak) { $peak = $d }
    }
    $lines | Out-File -FilePath $seriesFile -Encoding utf8
    $peakDbLoad = [math]::Round($peak, 1)
    Write-Host "      피크 db_load = $peakDbLoad / 초  ($seriesFile)"
} catch {
    Write-Host "      경고: Prometheus 조회 실패 - peak_db_load 를 비워 기록한다 ($_)" -ForegroundColor Yellow
}

# ── 7. 앱 메트릭 집계 ─────────────────────────────────────────────────
Write-Host '[7/8] 앱 메트릭 집계' -ForegroundColor Cyan
$metricsAfter = (Invoke-WebRequest -Uri 'http://localhost:8081/actuator/prometheus' -UseBasicParsing -TimeoutSec 10).Content
$hit = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_total{' -Contains 'result="hit'
$miss = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_total{' -Contains 'result="miss'
$dbLoad = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_db_load_total'
$waitTimeout = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_wait_timeout_total'
$hitRatio = ''
if (($hit + $miss) -gt 0) { $hitRatio = [math]::Round($hit / ($hit + $miss), 4) }

# 미스 전용 타이머 - C3 의 대기 비용 표(11단계)와 미스 창 d 역산(docs/cache-stampede.md 7장)이 여기 값을 쓴다.
# Prometheus 쿼리 없이 앱 메트릭 본문의 _sum/_count 로 평균을 낸다 (Get-MetricSum 으로 충분하다)
$loadSum = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_load_seconds_sum{'
$loadCount = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_load_seconds_count{'
$loadMeanMs = ''
if ($loadCount -gt 0) { $loadMeanMs = [math]::Round(($loadSum / $loadCount) * 1000, 2) }
$waitSum = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_wait_seconds_sum{'
$waitCount = Get-MetricSum -Text $metricsAfter -Prefix 'coupon_cache_wait_seconds_count{'
$waitMeanMs = ''
if ($waitCount -gt 0) { $waitMeanMs = [math]::Round(($waitSum / $waitCount) * 1000, 2) }
# load_count 는 db_load 와 같은 경로에서 올라간다 - 다르면 계측 버그다 (실행은 막지 않는다)
if ($loadCount -ne $dbLoad) {
    Write-Host "      경고: load_count($loadCount) != db_load($dbLoad) - 계측 버그 의심" -ForegroundColor Yellow
}
Write-Host "      hit=$hit miss=$miss db_load=$dbLoad hit_ratio=$hitRatio load_mean_ms=$loadMeanMs wait_timeout=$waitTimeout"

# k6 요약 JSON에서 p99·요청 수·read_error 를 읽는다
$summary = Get-Content $summaryJson -Raw -Encoding UTF8 | ConvertFrom-Json
$k6Reqs = 0; $k6P99Ms = ''; $readError = 0
if ($summary.metrics.http_reqs) { $k6Reqs = [int]$summary.metrics.http_reqs.count }
if ($summary.metrics.coupon_read_error) { $readError = [int]$summary.metrics.coupon_read_error.count }
$measureRead = $summary.metrics.'http_req_duration{phase:measure,name:read}'
if ($measureRead) { $k6P99Ms = [math]::Round($measureRead.'p(99)', 1) }
Write-Host "      k6_reqs=$k6Reqs k6_p99_ms=$k6P99Ms read_error=$readError"

# ── 8. 실행 이력 기록 ─────────────────────────────────────────────────
Write-Host '[8/8] 결과 기록' -ForegroundColor Cyan
$indexFile = Join-Path $weekDir 'runs.tsv'
if (-not (Test-Path $indexFile)) {
    "timestamp`tcache`trun`telapsed_s`tthresholds`tk6_reqs`tk6_p99_ms`tread_error`thit`tmiss`tdb_load`tpeak_db_load`thit_ratio`tload_mean_ms`tload_count`twait_mean_ms`twait_count`twait_timeout`tcommit" |
        Out-File -FilePath $indexFile -Encoding utf8
}
$commit = Invoke-Native { git -C $root rev-parse --short HEAD 2>$null }
if (-not $commit) { $commit = 'uncommitted' }
# 측정 경로만 본다 (run-experiment.ps1 과 같은 방식) - 감시 경로에 run-cache.ps1 을 포함시킨다
elseif (Invoke-Native {
    git -C $root status --porcelain --untracked-files=no -- app loadtest seed infra docker-compose.yml run-cache.ps1 2>$null
}) { $commit = "$commit-dirty" }
"$($startedAt.ToString('s'))`t$Cache`t$Run`t$elapsed`t$thresholds`t$k6Reqs`t$k6P99Ms`t$readError`t$hit`t$miss`t$dbLoad`t$peakDbLoad`t$hitRatio`t$loadMeanMs`t$loadCount`t$waitMeanMs`t$waitCount`t$waitTimeout`t$commit" |
    Out-File -FilePath $indexFile -Encoding utf8 -Append

Write-Host "=== [$tag] 완료 — 임계값 $thresholds, ${elapsed}초, 피크 db_load $peakDbLoad ===" -ForegroundColor Green
Write-Host "  요약   $summaryJson"
Write-Host "  로그   $k6Log"
Write-Host "  시계열 $seriesFile"
Write-Host "  앱 로그 $appLog"

# git rev-parse 등 앞선 네이티브 호출의 종료 코드가 스크립트 결과로 새지 않게 한다
exit 0

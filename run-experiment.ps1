# 실험 1회 실행 (전략 적용 -> 초기화 -> 부하 -> 정합성 판정 -> 결과 저장)
#
# 절차를 사람이 순서대로 지키는 방식은 반복 횟수가 늘면 반드시 어긋난다.
# (실제로 리셋 없이 두 번 돌려 결과가 오염된 적이 있다. PROJECT_BRIEF 7장 규칙)
#
# 사용법:
#   .\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1
#   .\run-experiment.ps1 -Strategy W1 -Scenario ramp  -Run 2 -K6Env HOLD=1m
#   .\run-experiment.ps1 -Strategy W0 -Scenario spike -Run 1 -PoolSize 10 -Week week3-experiment-a-stage2

param(
    [Parameter(Mandatory)][ValidateSet('W0', 'W1', 'W2', 'W3', 'W4')]
    [string]$Strategy,

    [Parameter(Mandatory)][ValidateSet('spike', 'ramp', 'soak')]
    [string]$Scenario,

    [Parameter(Mandatory)][ValidateRange(1, 10)]
    [int]$Run,

    # 결과가 쌓일 주차 폴더
    [string]$Week = 'week2-experiment-a',

    # 커넥션 총량 (확정값 D-01: 1단계 30, 2단계 인스턴스당 10)
    [int]$PoolSize = 30,

    # k6 에 넘길 추가 변수 (예: VUS=500,TOTAL=50000) - 축소 실행용. 정식 측정은 기본값(D-02)을 쓴다
    [string[]]$K6Env = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# PowerShell 5.1 은 파이프에 걸린 네이티브 명령의 stderr 를 오류로 승격시킨다.
# docker / k6 는 진행 상황을 stderr 로 쓰므로 정상 동작이 실행 실패로 둔갑한다.
function Invoke-Native {
    param([Parameter(Mandatory)][scriptblock]$Command)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $prev }
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
$integrityOut = Join-Path $intDir "$tag.txt"

Write-Host "=== [$tag] 시작 ===" -ForegroundColor Cyan
Write-Host "  전략=$Strategy  시나리오=$Scenario  회차=$Run  풀=$PoolSize  주차=$Week"

# ── 1. 전략 적용 후 앱 재기동 ──────────────────────────────────────────
Write-Host '[1/5] 전략 적용 + 앱 재기동' -ForegroundColor Cyan
$env:COUPON_STRATEGY = $Strategy
$env:DB_POOL_SIZE = $PoolSize
Invoke-Native { docker compose -f "$root\docker-compose.yml" up -d --force-recreate app } | Out-Null

# ── 2. 기동 대기 ───────────────────────────────────────────────────────
Write-Host '[2/5] 헬스체크 대기' -ForegroundColor Cyan
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

# ── 3. DB / Redis 초기화 ───────────────────────────────────────────────
Write-Host '[3/5] DB / Redis 초기화' -ForegroundColor Cyan
Invoke-Native { & "$root\seed\reset.ps1" } | Out-Null

# ── 4. 부하 실행 ───────────────────────────────────────────────────────
Write-Host '[4/5] k6 실행' -ForegroundColor Cyan
$k6Args = @('run', '--no-color', "--summary-export=$summaryJson")
foreach ($e in $K6Env) { $k6Args += @('-e', $e) }
$k6Args += "$root\loadtest\scenarios\$Scenario.js"

$startedAt = Get-Date
Invoke-Native { & k6 @k6Args } | Tee-Object -FilePath $k6Log
$k6Exit = $LASTEXITCODE
$elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)

# k6 종료 코드: 0=통과, 99=임계값 미달, 그 외=실행 실패
# 임계값 미달은 측정 결과이지 실행 실패가 아니므로 구분한다.
# 콘솔에 뜨는 "thresholds ... have been crossed" 는 k6 가 stderr 로 쓰는 정상 알림이다
$thresholds = switch ($k6Exit) {
    0 { 'PASS' }
    99 { 'FAIL' }
    default { throw "k6 실행 실패 (exit=$k6Exit). 로그 확인: $k6Log" }
}

# ── 5. 정합성 판정 ─────────────────────────────────────────────────────
Write-Host '[5/5] NFR-01 / NFR-02 판정' -ForegroundColor Cyan
Invoke-Native {
    Get-Content "$root\seed\verify.sql" -Raw -Encoding UTF8 |
        docker compose -f "$root\docker-compose.yml" exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1
} | Tee-Object -FilePath $integrityOut

# ── 실행 이력 기록 ─────────────────────────────────────────────────────
$indexFile = Join-Path $weekDir 'runs.tsv'
if (-not (Test-Path $indexFile)) {
    "timestamp`tstrategy`tscenario`trun`tpool`telapsed_s`tthresholds`tcommit" |
        Out-File -FilePath $indexFile -Encoding utf8
}
$commit = Invoke-Native { git -C $root rev-parse --short HEAD 2>$null }
if (-not $commit) { $commit = 'uncommitted' }
"$($startedAt.ToString('s'))`t$Strategy`t$Scenario`t$Run`t$PoolSize`t$elapsed`t$thresholds`t$commit" |
    Out-File -FilePath $indexFile -Encoding utf8 -Append

Write-Host "=== [$tag] 완료 — 임계값 $thresholds, ${elapsed}초 ===" -ForegroundColor Green
Write-Host "  요약  $summaryJson"
Write-Host "  로그  $k6Log"
Write-Host "  정합성 $integrityOut"

# git rev-parse 등 앞선 네이티브 호출의 종료 코드가 스크립트 결과로 새지 않게 한다
exit 0

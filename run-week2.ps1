# 2주차 측정 배치 러너 — run-experiment.ps1 을 41회 호출한다 (plan/week2-experiment-a.md 5장)
#
# 순서: ramp 1회차(5) → [멈춤: NFR-03 판정선 검토] → ramp 2·3회차(10) → spike(15) → chaos W3/W4(6) → soak(5)
# 회차 안에서 전략을 돌아가며 실행한다. 시간대에 따른 호스트 상태 변화가 특정 전략에만 쏠리지 않게 한다.
#
# 재개: results\<Week>\runs.tsv 에 이미 있는 (전략, 시나리오, 회차)는 건너뛴다.
# 실패한 실행은 기록되지 않으므로 다시 돌리면 그것만 다시 한다.
#
# 사용법:
#   .\run-week2.ps1 -Phase ramp1     # 약 1시간. 끝나면 500ms 판정선을 검토한다 (계획서 8단계)
#   .\run-week2.ps1 -Phase rest      # 나머지 36회, 약 8시간. 무인
#   .\run-week2.ps1                  # 전부 (all)
#   .\run-week2.ps1 -Phase soak-extra -SoakExtra W1,W2   # soak 우하향 전략만 2·3회차 (D-05)

param(
    [ValidateSet('ramp1', 'rest', 'all', 'soak-extra')]
    [string]$Phase = 'all',

    # soak-extra 전용 - 1회 스크리닝에서 우하향이 관측된 전략 (확정값 D-05)
    [string[]]$SoakExtra = @(),

    [string]$Week = 'week2-experiment-a'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$strategies = @('W0', 'W1', 'W2', 'W3', 'W4')

# ── 실행 목록 ──────────────────────────────────────────────────────────
$plan = New-Object System.Collections.Generic.List[object]
function Add-Runs([string]$scenario, [string[]]$targets, [int[]]$runs) {
    foreach ($r in $runs) {
        foreach ($s in $targets) {
            $plan.Add([pscustomobject]@{ Strategy = $s; Scenario = $scenario; Run = $r })
        }
    }
}
if ($Phase -in @('ramp1', 'all')) {
    Add-Runs 'ramp' $strategies @(1)
}
# soak 1회 스크리닝에서 우하향이 나온 전략만 2·3회차를 채운다 (확정값 D-05)
if ($Phase -eq 'soak-extra') {
    # powershell.exe -File 로 넘기면 "W1,W2" 가 한 덩어리 문자열로 들어온다. 직접 쪼갠다
    $targets = @($SoakExtra | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
    if (-not $targets) { throw '-SoakExtra 로 전략을 지정해야 한다 (예: -SoakExtra W1,W2)' }
    $unknown = @($targets | Where-Object { $_ -notin $strategies })
    if ($unknown) { throw "알 수 없는 전략: $($unknown -join ', ')" }
    Add-Runs 'soak' $targets @(2, 3)
}
if ($Phase -in @('rest', 'all')) {
    Add-Runs 'ramp'  $strategies @(2, 3)
    Add-Runs 'spike' $strategies @(1, 2, 3)
    Add-Runs 'chaos' @('W3', 'W4') @(1, 2, 3)
    Add-Runs 'soak'  $strategies @(1)          # 1회 스크리닝 (확정값 D-05)
}

# ── 재개 — 끝난 것은 건너뛴다 ──────────────────────────────────────────
$weekDir = Join-Path $root "results\$Week"
$indexFile = Join-Path $weekDir 'runs.tsv'
$done = @{}
if (Test-Path $indexFile) {
    Import-Csv -Path $indexFile -Delimiter "`t" | ForEach-Object {
        $done["$($_.strategy)/$($_.scenario)/$($_.run)"] = $true
    }
}
$pending = @($plan | Where-Object { -not $done["$($_.Strategy)/$($_.Scenario)/$($_.Run)"] })

Write-Host "=== 2주차 배치 [$Phase] — 계획 $($plan.Count)회, 완료 $($plan.Count - $pending.Count)회, 남은 $($pending.Count)회 ===" -ForegroundColor Cyan
if ($pending.Count -eq 0) { Write-Host '할 일이 없다'; exit 0 }

# ── 스택 준비 — 이미지를 현재 코드로 빌드하고 전체 스택을 올린다 ───────
# runs.tsv 의 commit 열이 실제로 측정에 쓰인 코드와 같아야 한다 (conditions.md)
$logDir = Join-Path $weekDir 'log'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
Start-Transcript -Path (Join-Path $logDir "batch-$Phase-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt") | Out-Null
try {
    Write-Host '[준비] 앱 이미지 빌드 + 스택 기동' -ForegroundColor Cyan
    docker compose -f "$root\docker-compose.yml" build app
    docker compose -f "$root\docker-compose.yml" up -d

    # ── 실행 ───────────────────────────────────────────────────────────
    $batchStart = Get-Date
    $ok = 0; $failed = @()
    foreach ($p in $pending) {
        $key = "$($p.Strategy)/$($p.Scenario)/$($p.Run)"
        Write-Host "`n>>> [$($ok + $failed.Count + 1)/$($pending.Count)] $key  (경과 $([math]::Round(((Get-Date) - $batchStart).TotalMinutes))분)" -ForegroundColor Magenta
        try {
            & "$root\run-experiment.ps1" -Strategy $p.Strategy -Scenario $p.Scenario -Run $p.Run -Week $Week
            $ok++
        } catch {
            # 한 번의 실패로 배치 전체를 멈추지 않는다. 기록되지 않았으므로 다음 실행 때 다시 한다
            Write-Host "!!! $key 실패: $_" -ForegroundColor Red
            $failed += $key
        }
    }

    Write-Host "`n=== 배치 [$Phase] 끝 — 성공 $ok, 실패 $($failed.Count), 총 $([math]::Round(((Get-Date) - $batchStart).TotalMinutes))분 ===" -ForegroundColor Green
    if ($failed.Count -gt 0) { Write-Host "  실패: $($failed -join ', ')  → 같은 명령으로 다시 돌리면 이것만 실행된다" -ForegroundColor Yellow }
    if ($Phase -eq 'ramp1') { Write-Host '  다음: ramp 결과로 NFR-03 판정선(P99 500ms)을 검토한 뒤 -Phase rest 를 돌린다' -ForegroundColor Yellow }
} finally {
    Stop-Transcript | Out-Null
}
exit 0

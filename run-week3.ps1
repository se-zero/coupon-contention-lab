# 3주차 전반 측정 배치 러너 — run-experiment.ps1 을 반복 호출한다 (plan/week3.md 5장)
#
# stage2: spike × W0~W4 × 회차 1·2·3 = 15회, 3 인스턴스 (D-10). 회차 안에서 전략을 돌아가며 실행한다.
#         배치가 끝나면 오버레이(nginx, app-worker)를 내리고 단일 인스턴스로 되돌린다 - 안 내리면
#         다음 단일 인스턴스 측정에 app-worker 의 풀 10 이 섞여 커넥션 예산 30 이 깨진다 (D-01). 실패해도(finally) 해체한다.
# soak-followup: W3 soak 2·3회차 + W0·W1 soak 4회차, 단일 인스턴스 (2주차 폴더의 회차 번호를 이어받는다 - D-05).
#         6단계(DB 계측)가 붙은 뒤에 돌린다. 지금은 목록과 배관만 만든다.
#
# 재개: results\<Week>\runs.tsv 에 이미 있는 (전략, 시나리오, 회차)는 건너뛴다.
# 실패한 실행은 기록되지 않으므로 다시 돌리면 그것만 다시 한다.
#
# 사용법:
#   .\run-week3.ps1 -Phase stage2          # 15회, 3 인스턴스, 약 2.5시간. 무인
#   .\run-week3.ps1 -Phase soak-followup   # 4회, 단일 인스턴스, 약 2.2시간. 무인

param(
    [Parameter(Mandatory)][ValidateSet('stage2', 'soak-followup')]
    [string]$Phase
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$strategies = @('W0', 'W1', 'W2', 'W3', 'W4')

if ($Phase -eq 'stage2') {
    $Week = 'week3-experiment-a-stage2'
} else {
    $Week = 'week3-soak-followup'
}

# ── 실행 목록 ──────────────────────────────────────────────────────────
$plan = New-Object System.Collections.Generic.List[object]
function Add-Runs([string]$scenario, [string[]]$targets, [int[]]$runs) {
    foreach ($r in $runs) {
        foreach ($s in $targets) {
            $plan.Add([pscustomobject]@{ Strategy = $s; Scenario = $scenario; Run = $r })
        }
    }
}
if ($Phase -eq 'stage2') {
    Add-Runs 'spike' $strategies @(1, 2, 3)
} else {
    # 2주차 폴더의 회차 번호를 이어받는다 - W0·W1 은 2주차에 3회를 마쳤다 (확정값 D-05)
    $plan.Add([pscustomobject]@{ Strategy = 'W3'; Scenario = 'soak'; Run = 2 })
    $plan.Add([pscustomobject]@{ Strategy = 'W3'; Scenario = 'soak'; Run = 3 })
    $plan.Add([pscustomobject]@{ Strategy = 'W0'; Scenario = 'soak'; Run = 4 })
    $plan.Add([pscustomobject]@{ Strategy = 'W1'; Scenario = 'soak'; Run = 4 })
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

Write-Host "=== 3주차 배치 [$Phase] — 계획 $($plan.Count)회, 완료 $($plan.Count - $pending.Count)회, 남은 $($pending.Count)회 ===" -ForegroundColor Cyan
if ($pending.Count -eq 0) { Write-Host '할 일이 없다'; exit 0 }

# ── 스택 준비 — 이미지를 현재 코드로 빌드하고 스택을 올린다 ────────────
# runs.tsv 의 commit 열이 실제로 측정에 쓰인 코드와 같아야 한다 (conditions.md)
$composeArgs3 = @('-f', "$root\docker-compose.yml", '-f', "$root\docker-compose.scale.yml")
$logDir = Join-Path $weekDir 'log'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
Start-Transcript -Path (Join-Path $logDir "batch-$Phase-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt") | Out-Null
try {
    Write-Host '[준비] 앱 이미지 빌드' -ForegroundColor Cyan
    docker compose -f "$root\docker-compose.yml" build app

    if ($Phase -eq 'stage2') {
        Write-Host '[준비] 3대 스택 기동' -ForegroundColor Cyan
        # 기본 파일만으로 up -d 하면 app 을 파일 기본값(호스트 8080, scale 1)으로 되돌리려 든다 -
        # 스택이 이미 3대로 떠 있으면(배치 중단 뒤 재개) nginx 가 8080 을 쥐고 있어 포트 충돌이 난다 (실측 버그).
        # 오버레이까지 한 번에 올려야 처음 시작이든 재개든 멱등이다. 인스턴스별 재기동은 run-experiment.ps1 [1/6]·[2/6] 이 매 실행마다 한다
        docker compose @composeArgs3 up -d --scale app=2
    } else {
        Write-Host '[준비] 단일 인스턴스 스택 기동' -ForegroundColor Cyan
        docker compose -f "$root\docker-compose.yml" up -d
    }

    # ── 실행 ───────────────────────────────────────────────────────────
    $batchStart = Get-Date
    $ok = 0; $failed = @()
    foreach ($p in $pending) {
        $key = "$($p.Strategy)/$($p.Scenario)/$($p.Run)"
        Write-Host "`n>>> [$($ok + $failed.Count + 1)/$($pending.Count)] $key  (경과 $([math]::Round(((Get-Date) - $batchStart).TotalMinutes))분)" -ForegroundColor Magenta
        try {
            if ($Phase -eq 'stage2') {
                & "$root\run-experiment.ps1" -Strategy $p.Strategy -Scenario $p.Scenario -Run $p.Run -Week $Week -Instances 3
            } else {
                & "$root\run-experiment.ps1" -Strategy $p.Strategy -Scenario $p.Scenario -Run $p.Run -Week $Week
            }
            $ok++
        } catch {
            # 한 번의 실패로 배치 전체를 멈추지 않는다. 기록되지 않았으므로 다음 실행 때 다시 한다
            Write-Host "!!! $key 실패: $_" -ForegroundColor Red
            $failed += $key
        }
    }

    Write-Host "`n=== 배치 [$Phase] 끝 — 성공 $ok, 실패 $($failed.Count), 총 $([math]::Round(((Get-Date) - $batchStart).TotalMinutes))분 ===" -ForegroundColor Green
    if ($failed.Count -gt 0) { Write-Host "  실패: $($failed -join ', ')  → 같은 명령으로 다시 돌리면 이것만 실행된다" -ForegroundColor Yellow }
} finally {
    if ($Phase -eq 'stage2') {
        # 오버레이 해체 → 단일 인스턴스로 복귀. 안 하면 다음 단일 인스턴스 측정에 app-worker 의 풀 10 이
        # 섞여 커넥션 예산 30 이 깨진다 (D-01). 실패해도(finally) 해체한다
        Write-Host '[정리] 3대 스택 해체 → 단일 인스턴스로 복귀' -ForegroundColor Cyan
        docker compose @composeArgs3 rm -sf nginx app-worker
        $env:DB_POOL_SIZE = 30
        docker compose -f "$root\docker-compose.yml" up -d --force-recreate --scale app=1 app
    }
    Stop-Transcript | Out-Null
}
exit 0

# DB 초기화 + 시드 재주입
#
# 매 측정 실행 전에 반드시 돌린다. (PROJECT_BRIEF 7장 실험 신뢰성 규칙)
# 사용법:  .\seed\reset.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '[reset] 시드 재주입' -ForegroundColor Cyan
Get-Content "$root\seed\seed.sql" -Raw -Encoding UTF8 |
    docker compose -f "$root\docker-compose.yml" exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1

Write-Host '[reset] Redis 초기화' -ForegroundColor Cyan
docker compose -f "$root\docker-compose.yml" exec -T redis redis-cli FLUSHALL

Write-Host '[reset] 완료' -ForegroundColor Green

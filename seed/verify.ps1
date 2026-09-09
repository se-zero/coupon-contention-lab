# NFR-01 / NFR-02 판정
#
# 부하 실행 직후에 돌린다.
# 사용법:  .\seed\verify.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Get-Content "$root\seed\verify.sql" -Raw -Encoding UTF8 |
    docker compose -f "$root\docker-compose.yml" exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1

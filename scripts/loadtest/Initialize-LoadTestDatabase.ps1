[CmdletBinding()]
param(
    [ValidatePattern('^lab_booking_loadtest$')]
    [string]$Database = 'lab_booking_loadtest',
    [string]$MySqlExecutable = $env:LOADTEST_MYSQL
)

$ErrorActionPreference = 'Stop'

if (-not $MySqlExecutable) {
    $MySqlExecutable = 'D:\mysql\bin\mysql.exe'
}
if (-not (Test-Path -LiteralPath $MySqlExecutable)) {
    throw "找不到 MySQL 客户端：$MySqlExecutable。请通过 -MySqlExecutable 或 LOADTEST_MYSQL 指定路径。"
}
if ($Database -ne 'lab_booking_loadtest') {
    throw '安全限制：压测初始化脚本只允许重建 lab_booking_loadtest。'
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceSql = Join-Path $repoRoot 'backend\src\main\resources\sql\lab-booking-rebuild-init.sql'
if (-not (Test-Path -LiteralPath $sourceSql)) {
    throw "找不到初始化 SQL：$sourceSql"
}

# The source initializer deliberately drops all tables. Rewrite only its target
# schema in memory, then stream it to MySQL; no developer database is touched.
$sql = Get-Content -LiteralPath $sourceSql -Raw
$sql = $sql -replace '(?m)^CREATE DATABASE IF NOT EXISTS lab_booking\b', "CREATE DATABASE IF NOT EXISTS $Database"
$sql = $sql -replace '(?m)^USE lab_booking;', "USE $Database;"

$sql | & $MySqlExecutable --user=root --host=127.0.0.1 --protocol=TCP --batch
if ($LASTEXITCODE -ne 0) {
    throw "初始化隔离库 $Database 失败。"
}

Write-Host "已重建隔离压测库 $Database。" -ForegroundColor Green

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [long]$SlotId,
    [Parameter(Mandatory)] [ValidateRange(1, 100000)] [int]$ExpectedQuota,
    [ValidateRange(0, 300)] [int]$WaitSeconds = 30,
    [ValidatePattern('^lab_booking_loadtest$')] [string]$Database = 'lab_booking_loadtest',
    [string]$MySqlExecutable = $env:LOADTEST_MYSQL
)

$ErrorActionPreference = 'Stop'
if (-not $MySqlExecutable) { $MySqlExecutable = 'D:\mysql\bin\mysql.exe' }
if (-not (Test-Path -LiteralPath $MySqlExecutable)) { throw "找不到 MySQL 客户端：$MySqlExecutable" }
if ($Database -ne 'lab_booking_loadtest') { throw '安全限制：只允许校验 lab_booking_loadtest。' }

function Invoke-LoadTestScalar([string]$Sql) {
    $value = & $MySqlExecutable --user=root --host=127.0.0.1 --protocol=TCP --database=$Database --batch --skip-column-names --execute=$Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL 校验失败：$Sql" }
    return ($value | Select-Object -Last 1).Trim()
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    $pending = [int](Invoke-LoadTestScalar "SELECT COUNT(*) FROM reservation_request WHERE slot_id = $SlotId AND status IN ('PENDING', 'PROCESSING');")
    if ($pending -eq 0 -or (Get-Date) -ge $deadline) { break }
    Start-Sleep -Seconds 1
} while ($true)

$row = Invoke-LoadTestScalar @"
SELECT CONCAT(
  s.total_quota, '|', s.remain_quota, '|',
  (SELECT COUNT(*) FROM reservation r WHERE r.slot_id = s.id AND r.status = 'BOOKED'), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status = 'PENDING'), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status = 'PROCESSING'), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status = 'SUCCESS'), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status = 'FAILED')
) FROM resource_slot s WHERE s.id = $SlotId;
"@

$parts = $row -split '\|'
if ($parts.Count -ne 7) { throw "未找到时段 $SlotId 或无法解析校验结果：$row" }
$total, $remain, $booked, $pending, $processing, $success, $failed = $parts | ForEach-Object { [int]$_ }

$summary = [pscustomobject]@{
    SlotId = $SlotId; TotalQuota = $total; RemainQuota = $remain; BookedReservations = $booked
    PendingRequests = $pending; ProcessingRequests = $processing; SuccessRequests = $success; FailedRequests = $failed
    ExpectedQuota = $ExpectedQuota
}
$summary | Format-List

if ($total -ne $ExpectedQuota -or $booked -gt $total -or $remain -lt 0 -or ($booked + $remain) -ne $total -or $pending -ne 0 -or $processing -ne 0) {
    throw '压测后校验未通过：存在超卖、余量不一致或异步请求未收敛。'
}
Write-Host '压测后数据校验通过：无超卖、余量一致、异步请求已收敛。' -ForegroundColor Green
